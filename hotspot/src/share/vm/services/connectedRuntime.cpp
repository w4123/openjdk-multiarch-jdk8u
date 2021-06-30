/*
 * Copyright 2019-2020 Azul Systems, Inc.  All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 only, as published by
 * the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE.  See the GNU General Public License version 2 for  more
 * details (a copy is included in the LICENSE file that accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version 2
 * along with this work; if not, write to the Free Software Foundation,Inc.,
 * 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Azul Systems, Inc., 1600 Plymouth Street, Mountain View,
 * CA 94043 USA, or visit www.azulsystems.com if you need additional information
 * or have any questions.
 *
 */

#include "precompiled.hpp"
#include "classfile/javaClasses.hpp"
#include "classfile/systemDictionary.hpp"
#include "memory/oopFactory.hpp"
#include "runtime/globals.hpp"
#include "runtime/interfaceSupport.hpp"
#include "runtime/javaCalls.hpp"
#include "runtime/mutex.hpp"
#include "runtime/virtualspace.hpp"
#include "services/connectedRuntime.hpp"
#include "utilities/align.hpp"
#include "utilities/hash.hpp"
#include "utilities/macros.hpp"

#if INCLUDE_CRS

#ifdef _MSC_VER // we need variable-length classes, don't use copy-constructors or assignments
#pragma warning( disable : 4200 )
#endif

#define DEBUG 0

const static char ARGS_ENV_VAR_NAME[] = "CRS_ARGUMENTS";
const static char USE_CRS_ARGUMENT[] = "useCRS";
const static char UNLOCK_CRS_ARGUMENT[] = "UnlockExperimentalCRS";
const static char USE_CRS_FORCE[] = "force";
const static char USE_CRS_AUTO[] = "auto";

volatile bool ConnectedRuntime::_should_notify = false;
volatile bool ConnectedRuntime::_is_init = false;
Klass* ConnectedRuntime::_agent_klass = NULL;
ConnectedRuntime::LogLevel ConnectedRuntime::_log_level = CRS_LOG_LEVEL_NOT_SET;

class AList {
public:
  class Item {
    Item* volatile _next;
  public:
    Item(): _next() {}
    Item(Item* next): _next(next) {}
    Item* next() const { return _next; }
    void set_next(Item* i) { _next = i; }
  };
private:
  Item* volatile _list;
  Item _marker;
public:
  AList(): _list() {}
  void add(Item* i);
  void add_list(Item *l);
  Item* remove();
  Item* head() const { return _list; }
};

void AList::add(Item* i) {
  Item *head;
  do {
    head = _list;
    if (head && head->next() == &_marker)
      continue;
    i->set_next(head);
  } while(Atomic::cmpxchg_ptr(i, &_list, head) != head);
}

void AList::add_list(Item* l) {
  Item *head;
  // l shall point to Items which are not being modified concurrently
  Item *tail = l;
  while (tail->next())
    tail = tail->next();
  do {
    head = _list;
    if (head && head->next() == &_marker)
      continue;
    tail->set_next(head);
  } while(Atomic::cmpxchg_ptr(l, &_list, head) != head);
}

AList::Item *AList::remove() {
  Item *head;
  Item lock(&_marker);
  do {
    head = _list;
    if (!head)
      return NULL;
    if (head->next() == &_marker)
      continue;
  } while(Atomic::cmpxchg_ptr(&lock, &_list, head) != head);
  // head is a true head now so head->next() is _next from that incarnation of _list we took it from
  // (i.e. it's impossible for head->next() to point to _next at time we were trying to xchg different to one
  // at the time we have actually xchg'd). so we can safely set it to _list
  _list = head->next();
  head->set_next(NULL);
  return head;
}

// numbers from 0 to max are reserved to CrsMessage types
// the negative numbers could be used to identify other entities
// the values shall be in sync with c.a.c.c.Agent001
enum CrsNotificationType {
  CRS_DRAIN_QUEUE_AND_STOP_COMMAND = -101,
  CRS_DRAIN_QUEUE_COMMAND = -100,
  CRS_USE_CRS_COMMAND,
  CRS_EVENT_TO_JAVA_CALL,
  CRS_MESSAGE_CLASS_LOAD = 0,
  CRS_MESSAGE_FIRST_CALL = 1,
  CRS_MESSAGE_DELETED,
  CRS_MESSAGE_CLASS_LOAD_BLOWN,
  CRS_MESSAGE_FIRST_CALL_BLOWN,
  CRS_MESSAGE_TYPE_COUNT,
  // CRS_MESSAGE_GCLOG
};

const char * const crs_message_type_name[] = {
  "class load",
  "first call",
  "deleted",
  "class load blown",
  "first call blown"
};

enum CrsMessageBackReferenceId {
  CRS_MESSAGE_BACK_REFERENCE_CLASS_LOAD,
  CRS_MESSAGE_BACK_REFERENCE_ID_COUNT
};

class TLB: public CHeapObj<mtTracing>, public AList::Item {
  uintx _pos;
  u1* _base;
  Thread *_owner;
  u1* _reference_message[CRS_MESSAGE_BACK_REFERENCE_ID_COUNT];

public:
  TLB(): _owner(), _base() {}
  u1 *base() const { return _base; }
  void set_base(u1* base) { _base = base; }
  void lease(Thread *thread) {
    assert(!_owner && thread, "sanity");
    _pos = 0;
    _owner = thread;
    for(int i=0; i<CRS_MESSAGE_BACK_REFERENCE_ID_COUNT; i++) _reference_message[i] = NULL;
  }
  void release() { assert(_owner != NULL, "sanity"); _owner = NULL; }
  Thread *owner() const { return _owner; }
  uintx pos() const { return _pos; }
  u1 *reference_message(CrsMessageBackReferenceId back_ref_id) const { return _reference_message[back_ref_id]; }
  u1* alloc(uintx size);
  void set_reference_message(CrsMessageBackReferenceId back_ref_id, u1 *message) { _reference_message[back_ref_id] = message; }
};

class TLBClosure: public Closure {
public:
  virtual void tlb_do(TLB *tlb) = 0;
};

class TLBManager {
  AList _free_list;
  AList _leased_list;
  AList _uncommitted_list;
  TLB *_buffers;
  ReservedSpace _rs;
  uintx _buffer_size;
  jint _num_committed;
  uintx _buffers_count;
  uintx _area_size;
  uintx _bytes_used;
  // temporarily holds the buffers popped from _leased_list during flush
  // need to be accessible because safepoint can happen during flush (only when flushing
  // single buffer) so need to have all buffers which contain the data accessible so
  // the data can be evacuated if metaspace is evicted.
  // does not need atomic access because only accessed by CRS flush thread or
  // inside a safepoint
  TLB *_not_finished;

  TLB *lease_buffer(Thread *thread);
  bool uncommit_buffer(TLB *buffer, TLB **uncommitted_list);
public:
  static const uintx align = sizeof(intptr_t);

  TLBManager(uintx area_size);
  ~TLBManager();
  void flush_buffers(TLBClosure *f, uintx committed_goal);
  uintx bytes_used() const;
  TLB *ensure(TLB* buffer, uintx size, Thread *thread);
  u1 *alloc(TLB* buffer, uintx size);
  uintx bytes_committed() const { return (uintx)_num_committed * _buffer_size; }
  void leased_buffers_do(TLBClosure *f);
};

class NativeMemory: public CHeapObj<mtTracing> {
  TLBManager _tlb_manager;
  uintx _previous_usage; // high usage watermark on previous flush
  bool _overflow;

public:
  NativeMemory(uintx size);
  ~NativeMemory();

  u1 *alloc(CrsMessageBackReferenceId ref_id, bool *is_reference, uintx size, uintx size_reference, Thread *thread);
  u1 *alloc(uintx size, Thread *thread);
  void flush(TRAPS);
  u1 *reference_message(CrsMessageBackReferenceId ref_id, Thread *thread);
  void buffers_do(TLBClosure *f);
  void release_buffers();
  void release_thread_buffer(Thread *thread) const;
  uintx bytes_used() const { return _tlb_manager.bytes_used(); }
};

u1* TLB::alloc(uintx size) {
  assert(_base, "must be initialized");
  u1* ptr = _base + _pos;
  _pos += align_up(size, TLBManager::align);
  return ptr;
}

TLBManager::TLBManager(uintx size): _bytes_used(), _not_finished() {
  // it's known that normal VM startup loads about 2k classes
  // each record takes about 72 bytes (144k)
  // about 11k different methods are executed
  // with record size 24 bytes (264k)
  // some memory is wasted at the time of flush because buffers are in use
  // based on real usage the size estimate is 640k for 64 bit system
  const uintx initialCommittedSizeEstimate = MIN2(LP64_ONLY(640*K) NOT_LP64(512*K), size);
  const uintx desiredBufferSize = 8*K; // so about 128 records in one buffer
  _buffers_count = size / desiredBufferSize;
  if (_buffers_count < 2)
    _buffers_count = 2;
  _buffer_size = align_up(size / _buffers_count, os::vm_page_size());
  if (_buffer_size > 1u<<16) { // the implementation assumes no more than 64k per buffer
    _buffer_size = 1u<<16;
    _buffers_count = size / _buffer_size;
  }
  _num_committed = (jint)MIN2(MAX2((uintx)1u, initialCommittedSizeEstimate / _buffer_size), _buffers_count);
  _area_size = _buffers_count * _buffer_size;
  _buffers = new TLB[_buffers_count];

  _rs = ReservedSpace(_area_size, os::vm_page_size());
  MemTracker::record_virtual_memory_type(_rs.base(), mtTracing);
  if (!os::commit_memory(_rs.base(), _num_committed * _buffer_size, false)) {
    ConnectedRuntime::disable("Unable to allocate CRS native memory buffers", false);
    return;
  }
  os::trace_page_sizes("Crs", _area_size,
                              _area_size,
                              os::vm_page_size(),
                              _rs.base(),
                              _rs.size());
  for (uintx i = 0; i < _buffers_count; i++)
    _buffers[i].set_base(((u1*)_rs.base()) + i * _buffer_size);
  for (intx i = _num_committed; --i >= 0; )
    _free_list.add(_buffers + i);
  for (intx i = (intx)_buffers_count; --i >= _num_committed; )
    _uncommitted_list.add(_buffers + i);
  if(DEBUG) tty->print_cr("allocated %u of %" PRIuPTR " buffers of %" PRIuPTR " size. area size requested %" PRIuPTR " actual %" PRIuPTR " (%p %" PRIxPTR ")",
      _num_committed, _buffers_count, _buffer_size, size, _area_size, _rs.base(), _rs.size());
}

TLBManager::~TLBManager() {
  os::uncommit_memory(_rs.base(), _area_size, !ExecMem);
  _area_size = 0;
  delete _buffers;
  _buffers = NULL;
}

TLB* TLBManager::lease_buffer(Thread *thread) {
  TLB *to_lease;

  // trivial case, try to obtain a buffer
  to_lease = (TLB*)_free_list.remove();
  if (!to_lease) {
    // no free buffers, try to allocate
    to_lease = (TLB*)_uncommitted_list.remove();
    if (to_lease) {
      // successfully got new area, allocate memory for it
      if (!os::commit_memory((char*)to_lease->base(), _buffer_size, false)) {
        // no physical memory, put buffer back
        _uncommitted_list.add(to_lease);
        return NULL;
      }
      Atomic::inc(&_num_committed);
      assert((uintx)_num_committed <= _buffers_count, "sanity");
    } else {
      // no memory available
      if (DEBUG) tty->print_cr("out of buffer space %u buffers committed %" PRIuPTR " bytes used", _num_committed, _bytes_used);
      return NULL;
    }
  }

  to_lease->lease(thread);
  _leased_list.add(to_lease);
  Atomic::add((intx)_buffer_size, (intx*)&_bytes_used);

  if(DEBUG) tty->print_cr("leased buffer %p", to_lease->base());

  return to_lease;
}

uintx TLBManager::bytes_used() const {
  return _bytes_used;
}

TLB *TLBManager::ensure(TLB* buffer, uintx size, Thread *thread) {
  assert(size <= _buffer_size, "size too big");
  if (buffer && _buffer_size - buffer->pos() >= size) {
    return buffer;
  }
  if (buffer) {
    assert(buffer->owner() == Thread::current(), "must be");
    buffer->release();
  }
  return lease_buffer(thread);
}

u1* TLBManager::alloc(TLB* buffer, uintx size) {
  assert(size <= _buffer_size - buffer->pos(), "invariant");
  if (buffer == NULL)
    return NULL;
  u1 *p = buffer->alloc(size);
  assert(p >= (u1*)_rs.base() && p + size <= (u1*)_rs.base() + _rs.size(), "sanity");
  return p;
}

void TLBManager::flush_buffers(TLBClosure* f, uintx committed_goal) {
  TLB *uncommitted = NULL;
  int count_leased = 0, count_released = 0, count_uncommitted = 0;
  committed_goal /= _buffer_size;
  uintx to_uncommit = (uintx)_num_committed > committed_goal ? ((uintx)_num_committed) - committed_goal : 0;
  do {
    TLB *to_flush = static_cast<TLB*>(_leased_list.remove());
    if (!to_flush)
      break;
    Thread *owner = to_flush->owner();
    if (owner)
      count_leased++;
    else
      count_released++;
    if (owner) {
      // not yet finished, do not attempt to flush because more data can be written
      to_flush->set_next(_not_finished);
      _not_finished = to_flush;
    } else {
      // may provoke safepoint which in turn may cause metaspace eviction
      f->tlb_do(to_flush);
      // add buffer to _free_list as soon as it is free
      Atomic::add(-(intx)_buffer_size, (intx*)&_bytes_used);
      if (to_uncommit && uncommit_buffer(to_flush, &uncommitted)) {
        to_uncommit--;
        count_uncommitted++;
      } else {
        _free_list.add(to_flush);
      }
    }
  } while (true);
  // return back all not flushed buffers
  if (_not_finished) {
    _leased_list.add_list(_not_finished);
    _not_finished = NULL;
  }
  while (to_uncommit) {
    TLB *b = static_cast<TLB*>(_free_list.remove());
    if (!b)
      break;
    if (uncommit_buffer(b, &uncommitted)) {
      to_uncommit--;
      count_uncommitted++;
    } else
      break;
  }
  if (uncommitted)
    _uncommitted_list.add_list(uncommitted);
  if(DEBUG) tty->print_cr(" flush leased %d released %d uncommitted %d",
          count_leased, count_released, count_uncommitted);
}

bool TLBManager::uncommit_buffer(TLB* buffer, TLB** uncommitted_list) {
  if (os::uncommit_memory((char*)buffer->base(), _buffer_size, !ExecMem)) {
    buffer->set_next(*uncommitted_list);
    *uncommitted_list = buffer;
    assert(_num_committed > 0, "sanity");
    Atomic::add(-1, &_num_committed);
    return true;
  }
  return false;
}

void TLBManager::leased_buffers_do(TLBClosure* f) {
  // warning, naked operation, caller is assumed to synchronize
  for (TLB *b = static_cast<TLB*>(_leased_list.head()); b; b = static_cast<TLB*>(b->next()))
    f->tlb_do(b);
  // traverse buffers which have been put aside during flush
  for (TLB *b = _not_finished; b; b = static_cast<TLB*>(b->next()))
    f->tlb_do(b);
}

NativeMemory::NativeMemory(uintx size):
_tlb_manager(size), _previous_usage(_tlb_manager.bytes_committed()), _overflow() {}

NativeMemory::~NativeMemory() {
}

u1* NativeMemory::alloc(CrsMessageBackReferenceId back_ref_id, bool *is_reference, uintx size, uintx size_reference, Thread *thread) {
  if (_overflow)
    return NULL;

  TLB *buffer = thread->crs_thread_locals()->buffer();
  TLB *new_buffer = _tlb_manager.ensure(buffer, size, thread);
  if (new_buffer != buffer) {
    thread->crs_thread_locals()->set_buffer(new_buffer);
    *is_reference = true;
  }
  if (new_buffer != NULL) {
    u1 *message = _tlb_manager.alloc(new_buffer, *is_reference ? size_reference : size);
    if (*is_reference)
      new_buffer->set_reference_message(back_ref_id, message);
    return message;
  }
  _overflow = true;
  return NULL;
}

u1* NativeMemory::alloc(uintx size, Thread *thread) {
  if (_overflow)
    return NULL;

  TLB *buffer = thread->crs_thread_locals()->buffer();
  TLB *new_buffer = _tlb_manager.ensure(buffer, size, thread);
  if (new_buffer != buffer) {
    thread->crs_thread_locals()->set_buffer(new_buffer);
  }
  if (new_buffer != NULL) {
    return _tlb_manager.alloc(new_buffer, size);
  }
  _overflow = true;
  return NULL;
}

u1* NativeMemory::reference_message(CrsMessageBackReferenceId ref_id, Thread *thread) {
  TLB *buffer = thread->crs_thread_locals()->buffer();
  return buffer ? buffer->reference_message(ref_id) : NULL;
}

void NativeMemory::buffers_do(TLBClosure* f) {
  _tlb_manager.leased_buffers_do(f);
}

void NativeMemory::release_thread_buffer(Thread *thread) const {
  assert(Thread::current() == thread || SafepointSynchronize::is_at_safepoint(), "sanity");

  TLB *buffer = thread->crs_thread_locals()->buffer();
  if (buffer) {
    buffer->release();
    thread->crs_thread_locals()->set_buffer(NULL);
  }
}

static class CRSEvent: public ResourceObj {
  public:
    enum Type {
      DRAIN_QUEUE_COMMAND = -1,
      USE_CRS_COMMAND = 0,
      CLASS_LOAD,
      GCLOG,
      TO_JAVA_CALL,
      FIRST_CALL
    };

    CRSEvent *next;
    Type type;

    CRSEvent(Type type): type(type) {}

    virtual void process(TRAPS) = 0;
} *event_queue_head, **event_queue_tail = &event_queue_head;

class CRSToJavaCallEvent: public CRSEvent {
  char *name;
  uintx name_length;

public:
  static bool _should_notify;

  CRSToJavaCallEvent(Symbol *holder_symbol, Symbol *method_symbol)
  : CRSEvent(TO_JAVA_CALL) {
    uintx holder_name_length = holder_symbol->utf8_length();
    uintx method_name_length = method_symbol->utf8_length();
    // TODO consider leasing NativeMemory buffers for event queue
    name = NEW_C_HEAP_ARRAY(char, holder_name_length+1+method_name_length+1, mtTracing);
    holder_symbol->as_C_string(name, holder_name_length+1);
    name[holder_name_length] = '.';
    method_symbol->as_C_string(&name[holder_name_length+1], method_name_length+1);
  }

  virtual void process(TRAPS) {
    // some notifications might be pending in the queue when event is disabled
    if (!_should_notify)
      return;

    ResourceMark rm(THREAD);
    HandleMark hm(THREAD);

    JavaValue res(T_VOID);
    Handle agentArgs = java_lang_String::create_from_str(name, CHECK);

    instanceKlassHandle mkh(THREAD, ConnectedRuntime::_agent_klass);
    JavaCalls::call_static(&res,
                           mkh,
                           vmSymbols::notifyToJavaCall_name(),
                           vmSymbols::string_void_signature(),
                           agentArgs,
                           THREAD);
    if (HAS_PENDING_EXCEPTION) {
#ifdef ASSERT
      tty->print_cr("notification failed");
      java_lang_Throwable::print(PENDING_EXCEPTION, tty);
      tty->cr();
#endif // ASSERT
      CLEAR_PENDING_EXCEPTION;
    }
  }

  virtual ~CRSToJavaCallEvent() {
    FREE_C_HEAP_ARRAY(char, name, mtTracing);
  }
};
bool CRSToJavaCallEvent::_should_notify = true;

class CrsMessage {
private:
  CrsNotificationType _type;
  u2 _size;

#if DEBUG
  static uintx _message_count[CRS_MESSAGE_TYPE_COUNT];
  static uintx _message_all_sizes[CRS_MESSAGE_TYPE_COUNT];
#endif

protected:
  CrsMessage(CrsNotificationType type, u4 size) : _type(type), _size((u2)size) {
    assert(size < 1u<<16, "sanity");
#if DEBUG
    _message_count[type]++;
    _message_all_sizes[type] += size;
#endif
  }

  void switch_type(CrsNotificationType new_type) {
#if DEBUG
    _message_count[_type]--;
    _message_all_sizes[_type] -= _size;
    _message_count[new_type]++;
    _message_all_sizes[new_type] += _size;
#endif
    _type = new_type;
  }

  static Klass* agent_klass() { return ConnectedRuntime::_agent_klass; }
public:
  u2 size() const { return _size; }
  CrsNotificationType type() const { return _type; }
  void process(TLB *tlb, TRAPS) const;
  void print_on(outputStream *s) const;

#if DEBUG
  static void print_statistics();
#endif
};

class CrsClassLoadMessage : public CrsMessage {
  InstanceKlass *_klass;
  crs_traceid _loaderId;
  crs_traceid _klass_id;
  struct {
    int has_hash: 1;
    int has_source: 1;
    int has_same_source: 1;
  } _flags;
  u1 _hash[DL_SHA256];
  char _source[];

  static bool _should_notify;

  CrsClassLoadMessage(uintx size, instanceKlassHandle ikh, u1 const *hash, const char *source, CrsClassLoadMessage *reference) :
  CrsMessage(CRS_MESSAGE_CLASS_LOAD, size), _flags() {
    _klass = ikh();
    _loaderId = _klass->class_loader_data()->crs_trace_id();
    _klass_id = _klass->crs_trace_id();
    assert(_klass_id, "must be known named klass");
    if (hash) {
      _flags.has_hash = 1;
      memcpy(this->_hash, hash, sizeof (this->_hash));
    }
    if (reference) {
      _flags.has_same_source = 1;
      assert(size <= sizeof(CrsClassLoadMessage) && size >= offset_of(CrsClassLoadMessage, _source), "sanity");
    } else if (source) {
      _flags.has_source = 1;
      strcpy(_source, source);
      assert(_source + strlen(source) + 1 == ((char*)this) + size ||
              (size > offset_of(CrsClassLoadMessage, _source) && size < sizeof(CrsClassLoadMessage)), "sanity");
    } else {
      assert(size <= sizeof(CrsClassLoadMessage) && size >= offset_of(CrsClassLoadMessage, _source), "sanity");
    }
  }

public:

static void post(NativeMemory *memory, instanceKlassHandle ikh, u1 const *hash, const char *source, Thread *thread) {
    CrsClassLoadMessage *previous_reference =
            (CrsClassLoadMessage*) memory->reference_message(CRS_MESSAGE_BACK_REFERENCE_CLASS_LOAD, thread);
    // sanity check reference message. it might have be set as reference by occasion,
    // because of buffer overflow but really it has no source
    if (previous_reference && !previous_reference->_flags.has_source)
      previous_reference = NULL;
    // normalize "" to NULL, the encoding assumes string is non-empty
    if (source && !*source)
      source = NULL;
    bool is_new_reference = (source && previous_reference ?
            strcmp(previous_reference->_source, source) :
            source && !previous_reference) != 0;
    const uintx size_reference = offset_of(CrsClassLoadMessage, _source) + (source ? strlen(source) + 1 : 0);
    const uintx size = is_new_reference ? size_reference : sizeof (CrsClassLoadMessage);
    void *msg = memory->alloc(CRS_MESSAGE_BACK_REFERENCE_CLASS_LOAD, &is_new_reference, size, size_reference, thread);
    if (msg)
      new(msg) CrsClassLoadMessage(
              is_new_reference ? size_reference : size,
              ikh, hash, source,
              is_new_reference ? NULL : previous_reference);
  }

  bool references(InstanceKlass *ik) const { return _klass == ik; }
  void process(TLB *tlb, TRAPS) const;
  void print_on(outputStream *s) const;
  void blow(NativeMemory *memory, TLB *tlb, Thread *thread);

  static void set_should_notify(bool enable) { _should_notify = enable; }
  static bool should_notify() { return _should_notify; }

  friend class CrsClassLoadMessageBlown;
};

class CrsClassLoadMessageBlown : public CrsMessage {
  crs_traceid _loaderId;
  crs_traceid _klass_id;
  struct {
    int has_hash: 1;
    int has_source: 1;
  } _flags;
  u1 _hash[DL_SHA256];
  char _source_and_name[];

  CrsClassLoadMessageBlown(uintx size, CrsClassLoadMessage *from_message, TLB *from_tlb, uintx source_size) :
  CrsMessage(CRS_MESSAGE_CLASS_LOAD_BLOWN, size) {
    _loaderId = from_message->_loaderId;
    _klass_id = from_message->_klass_id;
    _flags.has_hash = from_message->_flags.has_hash;
    _flags.has_source = from_message->_flags.has_source | from_message->_flags.has_same_source;
    memcpy(_hash, from_message->_hash, sizeof(_hash));

    if (from_message->_flags.has_source) {
      memcpy(&_source_and_name, from_message->_source, source_size);
    } else if (from_message->_flags.has_same_source) {
      memcpy(_source_and_name, ((CrsClassLoadMessage*)from_tlb->reference_message(CRS_MESSAGE_BACK_REFERENCE_CLASS_LOAD))->_source, source_size);
    }

    char *const name = &_source_and_name[source_size];
    from_message->_klass->name()->as_C_string(name, size - (name - (char*)this));
  }

public:

  static void post(NativeMemory *memory, TLB *from_tlb, CrsClassLoadMessage *from_message, Thread *thread) {
    uintx source_size;

    if (from_message->_flags.has_source) {
      source_size = from_message->size() - offset_of(CrsClassLoadMessage, _source);
    } else if (from_message->_flags.has_same_source) {
      CrsClassLoadMessage *reference = (CrsClassLoadMessage*)from_tlb->reference_message(CRS_MESSAGE_BACK_REFERENCE_CLASS_LOAD);
      assert(reference, "invariant");
      source_size = reference->size() - offset_of(CrsClassLoadMessage, _source);
    } else {
      source_size = 0;
    }

    const uintx size = offset_of(CrsClassLoadMessageBlown, _source_and_name)
                                  + source_size
                                  + from_message->_klass->name()->utf8_length() + 1;
    void *msg = memory->alloc(size, thread);
    if (msg)
      new(msg) CrsClassLoadMessageBlown(size, from_message, from_tlb, source_size);
  }

  void process(TRAPS) const;
  void print_on(outputStream *s) const;
};

class CrsFirstCallMessage : public CrsMessage {
  Method *_method;
  crs_traceid _holder_id;

  static bool _should_notify;

  CrsFirstCallMessage(Method *method):
      CrsMessage(CRS_MESSAGE_FIRST_CALL, sizeof(CrsFirstCallMessage)),
      _method(method), _holder_id(method->method_holder()->crs_trace_id()) {}
public:

  static void post(NativeMemory *memory, Method *method, Thread *thread) {
    void *msg = memory->alloc(sizeof(CrsFirstCallMessage), thread);
    if (msg)
      new (msg) CrsFirstCallMessage(method);
  }

  static void set_should_notify(bool enable) { _should_notify = enable; }
  static bool should_notify() { return _should_notify; }

  bool references(InstanceKlass *ik) const { return _holder_id == ik->crs_trace_id(); }
  bool references(Method *m) const { return _method == m; }
  bool references(Array<Method*>* methods) const;
  void process(TRAPS) const;
  void print_on(outputStream *s) const;
  void blow(NativeMemory *memory, Thread *thread);

  friend class CrsFirstCallMessageBlown;
};

class CrsFirstCallMessageBlown : public CrsMessage {
  crs_traceid _holder_id;
  char _method_name[];

  CrsFirstCallMessageBlown(uintx size, CrsFirstCallMessage *from_message):
      CrsMessage(CRS_MESSAGE_FIRST_CALL_BLOWN, size),
      _holder_id(from_message->_holder_id) {

    Symbol *name = from_message->_method->name();
    int name_length = name->utf8_length();
    name->as_C_string(_method_name, size - (_method_name - (char*)this));
    from_message->_method->signature()->as_C_string(&_method_name[name_length],
            size - (_method_name - (char*)this) - name_length);
  }
public:

  static void post(NativeMemory *memory, CrsFirstCallMessage *from_message, Thread *thread) {
    const uintx size = offset_of(CrsFirstCallMessageBlown, _method_name) +
      from_message->_method->name()->utf8_length() +
      from_message->_method->signature()->utf8_length() + 1;

    void *msg = memory->alloc(size, thread);

    if (msg)
      new (msg) CrsFirstCallMessageBlown(size, from_message);
  }

  void process(TRAPS) const;
  void print_on(outputStream *s) const;
};

class CrsDeletedMessage : public CrsMessage {
  CrsDeletedMessage(): CrsMessage(CRS_MESSAGE_DELETED, 0) {}
public:

  void print_on(outputStream *s) const;
};

class MessageClosure : public TLBClosure {
public:
  virtual void tlb_do(TLB* tlb);
protected:
  virtual void message_do(TLB *tlb, CrsMessage *message) = 0;
};

class TLBFlushClosure: public MessageClosure {
  Thread *_thread;
public:
  TLBFlushClosure(Thread *thread): _thread(thread) {}
  virtual void tlb_do(TLB *tlb);
  virtual void message_do(TLB *tlb, CrsMessage *msg);
};

void NativeMemory::flush(TRAPS) {
  const uintx next_target = (_previous_usage + _tlb_manager.bytes_used()) / 2;
  _previous_usage = _tlb_manager.bytes_used();

  if(DEBUG) tty->print_cr("CRS native buffers flush. %" PRIuPTR " bytes used. reserve %" PRIuPTR "->%" PRIuPTR,
      _previous_usage, _tlb_manager.bytes_committed(), next_target);
  uintx before = _tlb_manager.bytes_used();
  TLBFlushClosure f(THREAD);
  _tlb_manager.flush_buffers(&f, next_target);
  if (_overflow) {
    tty->print_cr("CRS native buffer overflow, data is lost [%" PRIuPTR "->%" PRIuPTR "]",
            before, _tlb_manager.bytes_used());
    _overflow = false;
  }
}

class TLBReleaseClosure: public TLBClosure {
public:
  virtual void tlb_do(TLB *tlb);
};

void NativeMemory::release_buffers() {
  TLBReleaseClosure f;
  _tlb_manager.leased_buffers_do(&f);
}

#if DEBUG
uintx CrsMessage::_message_count[CRS_MESSAGE_TYPE_COUNT];
uintx CrsMessage::_message_all_sizes[CRS_MESSAGE_TYPE_COUNT];
#endif

void CrsMessage::print_on(outputStream *s) const {
  ResourceMark rm;

  switch (type()) {
    case CRS_MESSAGE_CLASS_LOAD:
      static_cast<CrsClassLoadMessage const*>(this)->print_on(tty);
      break;
    case CRS_MESSAGE_CLASS_LOAD_BLOWN:
      static_cast<CrsClassLoadMessageBlown const*>(this)->print_on(tty);
      break;
    case CRS_MESSAGE_FIRST_CALL:
      static_cast<CrsClassLoadMessage const*>(this)->print_on(tty);
      break;
    case CRS_MESSAGE_FIRST_CALL_BLOWN:
      static_cast<CrsClassLoadMessageBlown const*>(this)->print_on(tty);
      break;
    case CRS_MESSAGE_DELETED:
      if (DEBUG) static_cast<CrsDeletedMessage const*>(this)->print_on(tty);
      break;
    default:
      ShouldNotReachHere();
  }
}

void CrsMessage::process(TLB *tlb, TRAPS) const {
  ResourceMark rm;

  switch (type()) {
    case CRS_MESSAGE_CLASS_LOAD:
      static_cast<CrsClassLoadMessage const *>(this)->process(tlb, THREAD);
      break;
    case CRS_MESSAGE_CLASS_LOAD_BLOWN:
      static_cast<CrsClassLoadMessageBlown const *>(this)->process(THREAD);
      break;
    case CRS_MESSAGE_FIRST_CALL:
      static_cast<CrsFirstCallMessage const *>(this)->process(THREAD);
      break;
    case CRS_MESSAGE_FIRST_CALL_BLOWN:
      static_cast<CrsFirstCallMessageBlown const *>(this)->process(THREAD);
      break;
    case CRS_MESSAGE_DELETED:
      break;
    default:
      if (DEBUG) tty->print_cr("unexpected message type %d", type());
      ShouldNotReachHere();
  }
}

#if DEBUG
void CrsMessage::print_statistics() {
  tty->print_cr("CRS message statistics");
  for (int i = 0; i < CRS_MESSAGE_TYPE_COUNT; i++)
    if (_message_count[i] > 0)
      tty->print_cr(" type %s count " PRIuPTR " total size " PRIuPTR, crs_message_type_name[i], _message_count[i], _message_all_sizes[i]);
}
#endif

bool CrsClassLoadMessage::_should_notify = true;

void CrsClassLoadMessage::print_on(outputStream* s) const {
  s->print_cr(" class load: %s ", _klass->name()->as_C_string());
}

void CrsClassLoadMessage::process(TLB *tlb, TRAPS) const {
    ResourceMark rm(THREAD);
    HandleMark hm(THREAD);

    assert(_klass->name(), "must point to valid Klass");

    JavaValue res(T_VOID);
    JavaCallArguments agentArgs;
    Handle name_handle;
    name_handle = java_lang_String::create_from_symbol(_klass->name(), CHECK);
    Handle source_handle;
    if (_flags.has_source) {
      source_handle = java_lang_String::create_from_str(_source, CHECK);
      tlb->set_reference_message(CRS_MESSAGE_BACK_REFERENCE_CLASS_LOAD, (u1*)this);
    } else if (_flags.has_same_source) {
      CrsClassLoadMessage const *ref =
          (CrsClassLoadMessage const *)(tlb->reference_message(CRS_MESSAGE_BACK_REFERENCE_CLASS_LOAD));
      assert(ref && ref->_flags.has_source, "sanity");
      source_handle = java_lang_String::create_from_str(ref->_source, CHECK);
      assert(size() <= sizeof(CrsClassLoadMessage), "sanity");
    }
    typeArrayOop hash_oop = NULL;
    if (_flags.has_hash) {
      hash_oop = oopFactory::new_byteArray(sizeof(_hash), CHECK);
      memcpy(hash_oop->byte_at_addr(0), _hash, sizeof(_hash));
    }
    typeArrayHandle hash_handle(THREAD, hash_oop);

    instanceKlassHandle mkh(THREAD, agent_klass());
    agentArgs.push_oop(name_handle);
    agentArgs.push_oop(hash_handle);
    agentArgs.push_int(_klass_id);
    agentArgs.push_int(_loaderId);
    agentArgs.push_oop(source_handle);
    JavaCalls::call_static(&res,
                           mkh,
                           vmSymbols::notifyClassLoad_name(),
                           vmSymbols::notifyClassLoad_signature(),
                           &agentArgs,
                           THREAD
      );
    if (HAS_PENDING_EXCEPTION) {
#ifdef ASSERT
      tty->print_cr("notification failed");
      java_lang_Throwable::print(PENDING_EXCEPTION, tty);
      tty->cr();
#endif // ASSERT
      CLEAR_PENDING_EXCEPTION;
    }
}

void CrsClassLoadMessageBlown::print_on(outputStream* s) const {
  s->print_cr(" class load: %s %s", _source_and_name, _flags.has_source ? &_source_and_name[strlen(_source_and_name+1)] : "");
}

void CrsClassLoadMessageBlown::process(TRAPS) const {
    ResourceMark rm(THREAD);
    HandleMark hm(THREAD);

    JavaValue res(T_VOID);
    JavaCallArguments agentArgs;
    char const *name = _source_and_name;
    Handle source_handle;
    if (_flags.has_source) {
      source_handle = java_lang_String::create_from_str(name, CHECK);
      name += strlen(name) + 1;
    }
    Handle name_handle = java_lang_String::create_from_str(name, CHECK);
    typeArrayOop hash_oop = NULL;
    if (_flags.has_hash) {
      hash_oop = oopFactory::new_byteArray(sizeof(_hash), CHECK);
      memcpy(hash_oop->byte_at_addr(0), _hash, sizeof(_hash));
    }
    typeArrayHandle hash_handle(THREAD, hash_oop);

    instanceKlassHandle mkh(THREAD, agent_klass());
    agentArgs.push_oop(name_handle);
    agentArgs.push_oop(hash_handle);
    agentArgs.push_int(_klass_id);
    agentArgs.push_int(_loaderId);
    agentArgs.push_oop(source_handle);
    JavaCalls::call_static(&res,
                           mkh,
                           vmSymbols::notifyClassLoad_name(),
                           vmSymbols::notifyClassLoad_signature(),
                           &agentArgs,
                           THREAD
      );
    if (HAS_PENDING_EXCEPTION) {
#ifdef ASSERT
      tty->print_cr("notification failed");
      java_lang_Throwable::print(PENDING_EXCEPTION, tty);
      tty->cr();
#endif // ASSERT
      CLEAR_PENDING_EXCEPTION;
    }
}

void CrsClassLoadMessage::blow(NativeMemory* memory, TLB *tlb, Thread* thread) {
  if (DEBUG)
    tty->print_cr("blow class load message klass %p %d", _klass, (int)_klass->crs_trace_id());

  CrsClassLoadMessageBlown::post(memory, tlb, this, thread);
  switch_type(CRS_MESSAGE_DELETED);
}

bool CrsFirstCallMessage::_should_notify = true;

void CrsFirstCallMessage::process(TRAPS) const {
    ResourceMark rm(THREAD);
    HandleMark hm(THREAD);

    JavaCallArguments agentArgs;
    JavaValue res(T_VOID);
    Handle methodName;
    uintx method_name_length = _method->name()->utf8_length();
    uintx method_sig_length = _method->signature()->utf8_length();
    char *name = NEW_C_HEAP_ARRAY(char, method_name_length + 1 + method_sig_length, mtTracing);
    if (!name) {
#ifdef ASSERT
      tty->print_cr("notification failed, out of scratch native memory");
#endif // ASSERT
      return;
    }
    _method->name()->as_C_string(name, method_name_length + 1);
    _method->signature()->as_C_string(&name[method_name_length], method_sig_length + 1);

    methodName = java_lang_String::create_from_str(name, CHECK);
    FREE_C_HEAP_ARRAY(char, name, mtTracing);

    agentArgs.push_int(_holder_id);
    agentArgs.push_oop(methodName);

    instanceKlassHandle mkh(THREAD, agent_klass());
    JavaCalls::call_static(&res,
                           mkh,
                           vmSymbols::notifyFirstCall_name(),
                           vmSymbols::notifyFirstCall_signature(),
                           &agentArgs,
                           THREAD);
    if (HAS_PENDING_EXCEPTION) {
#ifdef ASSERT
      tty->print_cr("notification failed");
      java_lang_Throwable::print(PENDING_EXCEPTION, tty);
      tty->cr();
#endif // ASSERT
      CLEAR_PENDING_EXCEPTION;
    }
}

void CrsFirstCallMessageBlown::process(TRAPS) const {
    ResourceMark rm(THREAD);
    HandleMark hm(THREAD);

    JavaCallArguments agentArgs;
    JavaValue res(T_VOID);
    Handle methodName = java_lang_String::create_from_str(_method_name, CHECK);

    agentArgs.push_int(_holder_id);
    agentArgs.push_oop(methodName);

    instanceKlassHandle mkh(THREAD, agent_klass());
    JavaCalls::call_static(&res,
                           mkh,
                           vmSymbols::notifyFirstCall_name(),
                           vmSymbols::notifyFirstCall_signature(),
                           &agentArgs,
                           THREAD);
    if (HAS_PENDING_EXCEPTION) {
#ifdef ASSERT
      tty->print_cr("notification failed");
      java_lang_Throwable::print(PENDING_EXCEPTION, tty);
      tty->cr();
#endif // ASSERT
      CLEAR_PENDING_EXCEPTION;
    }
}

void CrsFirstCallMessage::print_on(outputStream* s) const {
  s->print_cr(" first call: %s::%s%s ",
          _method->method_holder()->name()->as_C_string(),
          _method->name()->as_C_string(), _method->signature()->as_C_string());
}

void CrsFirstCallMessage::blow(NativeMemory* memory, Thread* thread) {
  CrsFirstCallMessageBlown::post(memory, this, thread);
  switch_type(CRS_MESSAGE_DELETED);
}

bool CrsFirstCallMessage::references(Array<Method*>* methods) const {
  if (methods != NULL && methods != Universe::the_empty_method_array() &&
      !methods->is_shared()) {
    for (int i = 0; i < methods->length(); i++) {
      Method* method = methods->at(i);
      if (method == NULL) continue;  // maybe null if error processing
      assert (!method->on_stack(), "shouldn't be called with methods on stack");
      if (references(method))
        return true;
    }
  }
  return false;
}

void CrsDeletedMessage::print_on(outputStream* st) const {
  st->print_cr(" deleted");
}

void MessageClosure::tlb_do(TLB* tlb) {
  u1 *p = tlb->base();
  u1 *f = p + tlb->pos();
  while (p < f) {
    CrsMessage *msg = (CrsMessage*)p;
    p += align_up(msg->size(), TLBManager::align);
    message_do(tlb, msg);
  }
}

void TLBFlushClosure::tlb_do(TLB* tlb) {
  if(/*DEBUG*/0) tty->print_cr("flush buffer %p, data size %" PRIuPTR, tlb, tlb->pos());
  MessageClosure::tlb_do(tlb);
}

void TLBFlushClosure::message_do(TLB *tlb, CrsMessage* msg) {
  msg->process(tlb, _thread);
}

void TLBReleaseClosure::tlb_do(TLB* tlb) {
  Thread *owner = tlb->owner();
  assert(SafepointSynchronize::is_at_safepoint() || Thread::current() == owner,
          "cannot flush active buffer asynchronously");
  // since we are on the same thread or in safepoint no concurrent modifications
  // to buffer can occur
  if (owner) {
    tlb->release();
    owner->crs_thread_locals()->set_buffer(NULL);
  }
}

static bool _event_gclog = false;
static NativeMemory *memory = NULL;

class VM_CRSOperation: public VM_Operation {
  bool (*_op_pre)();
  void (*_op_do)();
  bool _and_stop;

public:
  VM_CRSOperation(bool (*op_pre)(), void (*op_do)(), bool and_stop): VM_Operation(),
          _op_pre(op_pre), _op_do(op_do), _and_stop(and_stop) {}

  virtual VMOp_Type type() const { return VMOp_CRSOperation; }

  virtual bool doit_prologue() {
    return !_op_pre || (*_op_pre)();
  }

  virtual void doit() {
    assert(SafepointSynchronize::is_at_safepoint(), "must be");
    (*_op_do)();
    if (_and_stop) {
      CrsFirstCallMessage::set_should_notify(false);
      CrsClassLoadMessage::set_should_notify(false);
    }
  }
};

void ConnectedRuntime::init() {
  parse_options();

  if (UseCRS) {
    if (_log_level == CRS_LOG_LEVEL_NOT_SET)
      _log_level = CRS_LOG_LEVEL_ERROR;

    memory = new NativeMemory(CRSNativeMemoryAreaSize);
  }
}

/*
 * Compares two strings, value1 must be '\0'-terminated, length of value2 is supplied in value2_len argument
 */
static bool strnequals(const char *value1, const char *value2, int value2_len) {
  return !strncmp(value1, value2, value2_len) && !value1[value2_len];
}

void ConnectedRuntime::parse_log_level(LogLevel *var, const char *value, int value_len) {
  static const char * const values[] = { "trace", "debug", "info", "warning", "error", "off" };
  for (size_t i=0; i<sizeof(values)/sizeof(values[0]); i++) {
    if (strnequals(values[i], value, value_len)) {
       *var = static_cast<LogLevel>(i);
       break;
    }
  }
}

void ConnectedRuntime::parse_arguments(const char *arguments, bool needs_unlock) {
  static const char * const options[] = { "log", "log+vm", USE_CRS_ARGUMENT, UNLOCK_CRS_ARGUMENT };

  LogLevel global_log_level = CRS_LOG_LEVEL_NOT_SET;
  LogLevel vm_log_level = CRS_LOG_LEVEL_NOT_SET;

  bool use_crs = false; // true if useCRS is set
  bool unlock_is_set = false; // true if UnlockExperimentalCRS is set

  const char *comma;
  const char *equals;
  while (true) {
    comma = strchr(arguments, ',');
    equals = strchr(arguments, '=');
    if (!comma)
      comma = arguments + strlen(arguments);
    if (equals && equals < comma) {
      for (size_t i=0; i<sizeof(options)/sizeof(options[0]); i++)
        if (!strncmp(arguments, options[i], equals-arguments)) {
          const char *value = equals+1;
          const int value_len = comma-value;
          switch (i) {
            case 0: // log
              parse_log_level(&global_log_level, value, value_len);
              break;
            case 1: // log+vm
              parse_log_level(&vm_log_level, value, value_len);
              break;
            case 2: // UseCRS
              if (strnequals(USE_CRS_AUTO, value, value_len) ||
                      strnequals(USE_CRS_FORCE, value, value_len))
                use_crs = true;
              break;
          }
        }
    } else {
      if (strnequals(USE_CRS_ARGUMENT, arguments, comma-arguments))
          use_crs = true;
      else if (strnequals(UNLOCK_CRS_ARGUMENT, arguments, comma-arguments))
          unlock_is_set = true;
    }

    if (!*comma)
      break;

    arguments = comma+1;
  }

  if (use_crs && (!needs_unlock || unlock_is_set))
      FLAG_SET_DEFAULT(UseCRS, true);

  if (vm_log_level != CRS_LOG_LEVEL_NOT_SET)
    _log_level = vm_log_level;
  else if (global_log_level != CRS_LOG_LEVEL_NOT_SET)
    _log_level = global_log_level;
}

void ConnectedRuntime::parse_options() {

  const int ENV_ARGS_LENGTH = 4096;
  char env_args[ENV_ARGS_LENGTH];
  if (os::getenv(ARGS_ENV_VAR_NAME, env_args, sizeof(env_args)-1))
    parse_arguments(env_args, true);
  if (CRSArguments)
    parse_arguments(CRSArguments, false);
}

void ConnectedRuntime::engage(TRAPS) {
  if (UseCRS) {
    ResourceMark rm(THREAD);
    HandleMark hm(THREAD);

    // Engage the CRS daemons
    Handle loader = Handle(THREAD, SystemDictionary::java_system_loader());
    instanceKlassHandle agent_loader(THREAD,
            SystemDictionary::resolve_or_null(vmSymbols::com_azul_crs_agent_AgentLoader(),
                                                          loader,
                                                          Handle(),
                                                          THREAD));
    if (agent_loader.not_null() && !HAS_PENDING_EXCEPTION) {
      JavaValue obj_result(T_OBJECT);
      JavaCalls::call_static(&obj_result,
                            agent_loader,
                            vmSymbols::main_name(),
                            vmSymbols::void_object_signature(),
                            THREAD);

      oop agent_class_oop = (oop)obj_result.get_jobject();
      if (agent_class_oop && !HAS_PENDING_EXCEPTION) {
        // anchor the agent class so it's not taken by GC
        JNIHandles::make_global(Handle(agent_class_oop));
        _agent_klass = java_lang_Class::as_Klass(agent_class_oop);
        instanceKlassHandle agent_klass_handle(THREAD, _agent_klass);

        JavaValue void_result(T_VOID);
        Handle agentArgs;
        agentArgs = java_lang_String::create_from_str(CRSArguments, THREAD);
        if (!HAS_PENDING_EXCEPTION) {
          JavaCalls::call_static(&void_result,
                                 agent_klass_handle,
                                 vmSymbols::startAgent_name(),
                                 vmSymbols::string_void_signature(),
                                 agentArgs,
                                 THREAD);
        }
      }
    }
    if (!_agent_klass || HAS_PENDING_EXCEPTION) {
      // enable default logging level (ERROR) and report the problem
      // except if CRS or it's logging was not explicitly enabled and the problem
      // is caused by absence of CRS agent. in the latter case AgentLoader does
      // not throw but returns null
      if (HAS_PENDING_EXCEPTION && _log_level == CRS_LOG_LEVEL_NOT_SET)
        _log_level = CRS_LOG_LEVEL_ERROR;

      disable("Cannot start Connected Runtime Services", true);
      if (HAS_PENDING_EXCEPTION) {
        if (_log_level == CRS_LOG_LEVEL_TRACE) {
          java_lang_Throwable::print(PENDING_EXCEPTION, tty);
          tty->cr();
        }
        CLEAR_PENDING_EXCEPTION;
      }
      return;
    }

    // XXX membar
    _is_init = true;
  }
}

static void release_memory_do() {
  for (JavaThread* tp = Threads::first(); tp != NULL; tp = tp->next()) {
    tp->crs_thread_locals()->set_buffer(NULL);
  }

  delete memory;
  memory = NULL;
}

void ConnectedRuntime::disable(const char* msg, bool need_safepoint) {
  if (msg && _log_level <= CRS_LOG_LEVEL_ERROR) {
    tty->print_cr("CRS agent initialization failure: %s\n"
          "Disabling Connected Runtime services.", msg);
  }
  FLAG_SET_DEFAULT(UseCRS, false);

  if (memory) {
    if (need_safepoint) {
      VM_CRSOperation op(NULL, &release_memory_do, true);
      VMThread::execute(&op);
    } else {
      delete memory;
      memory = NULL;
    }
  }
}

void ConnectedRuntime::notify_class_load(instanceKlassHandle ikh, u1 const *hash, uintx hash_length, char const *source, TRAPS) {
  if (UseCRS && CrsClassLoadMessage::should_notify()) {
    assert(hash_length == DL_SHA256, "sanity");
    CrsClassLoadMessage::post(memory, ikh, hash, source, THREAD);
  }
}

void ConnectedRuntime::notify_tojava_call(methodHandle* m) {
  // ignore VM startup
  if (!UseCRS || !_is_init || !CRSToJavaCallEvent::_should_notify)
    return;

  methodHandle method = *m;
  // skip initializers
  if (method->is_static_initializer() || method->is_initializer())
    return;
  InstanceKlass *holder = method->method_holder();
  // ignore own calls
  if (holder == _agent_klass)
    return;

  // calls from native into Java must be processed by CRS agent rather quickly
  // at the same time synchronized processing does not impose noticeable overhead compared to existing
  // so we use event queue, drained by service thread for this purpose
  // TODO consider using NativeMemory buffers instead of C_HEAP
  schedule(new(ResourceObj::C_HEAP, mtTracing) CRSToJavaCallEvent(holder->name(), method->name()));
}

void ConnectedRuntime::notify_first_call(JavaThread *thread, Method *method) {
  if (UseCRS && CrsFirstCallMessage::should_notify()) {
    if (DEBUG) tty->print_cr("method call %p holder %p %d", method, method->method_holder(), (int)method->method_holder()->crs_trace_id());
    CrsFirstCallMessage::post(memory, method, thread);
  }
}

void ConnectedRuntime::notify_metaspace_eviction(InstanceKlass *ik, Array<Method*>* methods) {
  if (!UseCRS)
    return;

  assert(SafepointSynchronize::is_at_safepoint(), "only supported in safepoint");

  if (DEBUG) tty->print_cr("deallocate class %p %d methods %p", ik, (int)ik->crs_trace_id(), methods);

  class TLBKlassEvictionIntrospector: public MessageClosure {
    InstanceKlass *_ik;
    Array<Method*> *_methods;

  public:
    TLBKlassEvictionIntrospector(InstanceKlass *ik, Array<Method*>* methods): _ik(ik), _methods(methods) {}

    virtual void message_do(TLB *tlb, CrsMessage *message) {
      switch (message->type()) {
        case CRS_MESSAGE_CLASS_LOAD: {
          CrsClassLoadMessage *m = static_cast<CrsClassLoadMessage*>(message);
          if (m->references(_ik))
            m->blow(memory, tlb, VMThread::vm_thread());
        }
          break;
        case CRS_MESSAGE_FIRST_CALL: {
            CrsFirstCallMessage *m = static_cast<CrsFirstCallMessage*>(message);
            // methods from methods might be linked to different klass now, we cannot
            // find them by searching ik instance. so have to actually traverse the array
            if ((_methods && m->references(_methods)) || m->references(_ik))
              m->blow(memory, VMThread::vm_thread());
          }
          break;
        case CRS_MESSAGE_CLASS_LOAD_BLOWN:
        case CRS_MESSAGE_FIRST_CALL_BLOWN:
        case CRS_MESSAGE_DELETED:
          // not applicable
          break;
        default:
          if(DEBUG) tty->print_cr("unexpected message type %d", (int)message->type());
          ShouldNotReachHere();
      }
    }
  } introspector(ik, methods);

  memory->buffers_do(&introspector);
}

void ConnectedRuntime::notify_metaspace_eviction(Method *m) {
  if (!UseCRS)
    return;

  assert(SafepointSynchronize::is_at_safepoint(), "only supported in safepoint");

  if (DEBUG) tty->print_cr("deallocate method %p", m);

  class TLBMethodEvictionIntrospector: public MessageClosure {
    Method *_m;

  public:
    TLBMethodEvictionIntrospector(Method *m): _m(m) {}

    virtual void message_do(TLB *tlb, CrsMessage *message) {
      switch (message->type()) {
        case CRS_MESSAGE_FIRST_CALL: {
          CrsFirstCallMessage *m = static_cast<CrsFirstCallMessage*>(message);
          if (m->references(_m))
            m->blow(memory, VMThread::vm_thread());
        }
          break;
        case CRS_MESSAGE_CLASS_LOAD:
        case CRS_MESSAGE_FIRST_CALL_BLOWN:
        case CRS_MESSAGE_CLASS_LOAD_BLOWN:
        case CRS_MESSAGE_DELETED:
          // not applicable
          break;
        default:
          if(DEBUG) tty->print_cr("unexpected message type %d", (int)message->type());
          ShouldNotReachHere();
      }
    }
  } introspector(m);

  memory->buffers_do(&introspector);
}

void ConnectedRuntime::notify_thread_exit(Thread* thread) {
  memory->release_thread_buffer(thread);
}

void ConnectedRuntime::schedule(CRSEvent *event) {
  MutexLockerEx ml(Service_lock, Mutex::_no_safepoint_check_flag);

  _should_notify = true;

  *event_queue_tail = event;
  event_queue_tail = &(event->next);

  if (_is_init)
    Service_lock->notify_all();
}

bool ConnectedRuntime::should_notify_java() {
  return _should_notify;
}

void ConnectedRuntime::notify_java(TRAPS) {
  if (!_is_init) // not yet init, need to wait
    return;

  bool more = true;
  while (more) {
    CRSEvent *e;
    {
      MutexLockerEx ml(Service_lock, Mutex::_no_safepoint_check_flag);

      _should_notify = false;

      e = event_queue_head;
      if (event_queue_tail == &event_queue_head) {
        break;
      } else if (event_queue_tail == &(event_queue_head->next)) {
        event_queue_tail = &event_queue_head;
        more = false;
      } else {
        event_queue_head = e->next;
    }
  }
    e->process(THREAD);
    delete e;
  }
}

static bool release_buffers_pre() {
  return memory->bytes_used() > 0;
}

static void release_buffers_do() {
  memory->release_buffers();
}

void ConnectedRuntime::flush_buffers(bool force, bool and_stop, TRAPS) {
  if (!_is_init) // not yet init, need to wait
    return;

  if (force) {
    // force release all currently used buffers. must synchronize
    // in order to avoid inconsistent event stream at shutdown need to disable
    // all events if and_stop is set
    VM_CRSOperation release_buffers_op(&release_buffers_pre, &release_buffers_do, and_stop);
    VMThread::execute(&release_buffers_op);
  }

  memory->flush(THREAD);

#if DEBUG
  if (force)
    CrsMessage::print_statistics();
#endif
}

void ConnectedRuntime::assign_trace_id(ClassLoaderData* cld) {
  static crs_traceid cld_traceid = 0;
  if (cld->is_anonymous())
    cld->set_crs_trace_id((crs_traceid)0);
  else
    cld->set_crs_trace_id((crs_traceid)Atomic::add(1, &cld_traceid));
}

void ConnectedRuntime::assign_trace_id(InstanceKlass *ik) {
  static crs_traceid ik_traceid = 0;
  ik->set_crs_trace_id((crs_traceid)Atomic::add(1, &ik_traceid));
}

void ConnectedRuntime::mark_anonymous(InstanceKlass *ik) {
  ik->set_crs_trace_id(0);
}

JVM_ENTRY_NO_ENV(void, crs_Agent001_setNativeEventFilter(JNIEnv *env, jclass unused, jint event, jboolean enabled_value))
  bool enabled = enabled_value != JNI_FALSE;
  switch (event) {
    case CRS_USE_CRS_COMMAND:
      if (enabled != UseCRS) {
        if (enabled == false) {
#ifdef ASSERT
          tty->print_cr("Disabling Connected Runtime services.");
#endif
          ConnectedRuntime::disable(NULL, true);
        } else {
          assert(false, "cannot enable CRS which is already disabled");
        }
      }
      break;
    case CRS_EVENT_TO_JAVA_CALL:
      CRSToJavaCallEvent::_should_notify = enabled;
      break;
    case CRS_MESSAGE_FIRST_CALL:
      CrsFirstCallMessage::set_should_notify(enabled);
      break;
    case CRS_DRAIN_QUEUE_COMMAND:
    case CRS_DRAIN_QUEUE_AND_STOP_COMMAND:
      ConnectedRuntime::flush_buffers(enabled, event == CRS_DRAIN_QUEUE_AND_STOP_COMMAND, thread);
      break;
  }
JVM_END

static JNINativeMethod methods[] = {
    {(char*)"setNativeEventFilter",  (char*)"(IZ)V", (void *)&crs_Agent001_setNativeEventFilter}
};

JVM_ENTRY(void, crs_register_natives(JNIEnv *env, jclass clazz, jclass agent_clazz))
  ThreadToNativeFromVM ttnfv(thread);
  env->RegisterNatives(agent_clazz, methods, 1);
JVM_END

#endif // INCLUDE_CRS
