/*
 * Copyright (c) 2015, Red Hat, Inc. and/or its affiliates.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#ifndef SHARE_VM_GC_SHENANDOAH_SHENANDOAHHEAP_INLINE_HPP
#define SHARE_VM_GC_SHENANDOAH_SHENANDOAHHEAP_INLINE_HPP

#include "gc_implementation/shared/markBitMap.inline.hpp"
#include "memory/threadLocalAllocBuffer.inline.hpp"
#include "gc_implementation/shenandoah/brooksPointer.inline.hpp"
#include "gc_implementation/shenandoah/shenandoahAsserts.hpp"
#include "gc_implementation/shenandoah/shenandoahBarrierSet.inline.hpp"
#include "gc_implementation/shenandoah/shenandoahCollectionSet.hpp"
#include "gc_implementation/shenandoah/shenandoahCollectionSet.inline.hpp"
#include "gc_implementation/shenandoah/shenandoahControlThread.hpp"
#include "gc_implementation/shenandoah/shenandoahMarkingContext.inline.hpp"
#include "gc_implementation/shenandoah/shenandoahHeap.hpp"
#include "gc_implementation/shenandoah/shenandoahHeapRegionSet.hpp"
#include "gc_implementation/shenandoah/shenandoahHeapRegion.inline.hpp"
#include "gc_implementation/shenandoah/shenandoahUtils.hpp"
#include "oops/oop.inline.hpp"
#include "runtime/atomic.hpp"
#include "runtime/prefetch.hpp"
#include "runtime/prefetch.inline.hpp"
#include "utilities/copy.hpp"
#include "utilities/globalDefinitions.hpp"

template <class T>
void ShenandoahUpdateRefsClosure::do_oop_work(T* p) {
  T o = oopDesc::load_heap_oop(p);
  if (! oopDesc::is_null(o)) {
    oop obj = oopDesc::decode_heap_oop_not_null(o);
    _heap->update_with_forwarded_not_null(p, obj);
  }
}

void ShenandoahUpdateRefsClosure::do_oop(oop* p)       { do_oop_work(p); }
void ShenandoahUpdateRefsClosure::do_oop(narrowOop* p) { do_oop_work(p); }

inline ShenandoahHeapRegion* ShenandoahRegionIterator::next() {
  size_t new_index = Atomic::add((size_t) 1, &_index);
  // get_region() provides the bounds-check and returns NULL on OOB.
  return _heap->get_region(new_index - 1);
}

inline bool ShenandoahHeap::has_forwarded_objects() const {
  return _gc_state.is_set(HAS_FORWARDED);
}

inline size_t ShenandoahHeap::heap_region_index_containing(const void* addr) const {
  uintptr_t region_start = ((uintptr_t) addr);
  uintptr_t index = (region_start - (uintptr_t) base()) >> ShenandoahHeapRegion::region_size_bytes_shift();
  assert(index < num_regions(), err_msg("Region index is in bounds: " PTR_FORMAT, p2i(addr)));
  return index;
}

inline ShenandoahHeapRegion* const ShenandoahHeap::heap_region_containing(const void* addr) const {
  size_t index = heap_region_index_containing(addr);
  ShenandoahHeapRegion* const result = get_region(index);
  assert(addr >= result->bottom() && addr < result->end(), err_msg("Heap region contains the address: " PTR_FORMAT, p2i(addr)));
  return result;
}

template <class T>
inline oop ShenandoahHeap::update_with_forwarded_not_null(T* p, oop obj) {
  if (in_collection_set(obj)) {
    shenandoah_assert_forwarded_except(p, obj, is_full_gc_in_progress() || cancelled_gc() || is_degenerated_gc_in_progress());
    obj = ShenandoahBarrierSet::resolve_forwarded_not_null(obj);
    oopDesc::encode_store_heap_oop(p, obj);
  }
#ifdef ASSERT
  else {
    shenandoah_assert_not_forwarded(p, obj);
  }
#endif
  return obj;
}

template <class T>
inline oop ShenandoahHeap::maybe_update_with_forwarded(T* p) {
  T o = oopDesc::load_heap_oop(p);
  if (! oopDesc::is_null(o)) {
    oop obj = oopDesc::decode_heap_oop_not_null(o);
    return maybe_update_with_forwarded_not_null(p, obj);
  } else {
    return NULL;
  }
}

inline oop ShenandoahHeap::atomic_compare_exchange_oop(oop n, oop* addr, oop c) {
  return (oop) Atomic::cmpxchg_ptr(n, addr, c);
}

inline oop ShenandoahHeap::atomic_compare_exchange_oop(oop n, narrowOop* addr, oop c) {
  narrowOop cmp = oopDesc::encode_heap_oop(c);
  narrowOop val = oopDesc::encode_heap_oop(n);
  return oopDesc::decode_heap_oop((narrowOop) Atomic::cmpxchg(val, addr, cmp));
}

template <class T>
inline oop ShenandoahHeap::maybe_update_with_forwarded_not_null(T* p, oop heap_oop) {
  shenandoah_assert_not_in_cset_loc_except(p, !is_in(p) || is_full_gc_in_progress() || is_degenerated_gc_in_progress());
  shenandoah_assert_correct(p, heap_oop);

  if (in_collection_set(heap_oop)) {
    oop forwarded_oop = ShenandoahBarrierSet::resolve_forwarded_not_null(heap_oop);

    shenandoah_assert_forwarded_except(p, heap_oop, is_full_gc_in_progress() || is_degenerated_gc_in_progress());
    shenandoah_assert_not_in_cset_except(p, forwarded_oop, cancelled_gc());

    // If this fails, another thread wrote to p before us, it will be logged in SATB and the
    // reference be updated later.
    oop result = atomic_compare_exchange_oop(forwarded_oop, p, heap_oop);

    if (oopDesc::unsafe_equals(result, heap_oop)) { // CAS successful.
      return forwarded_oop;
    } else {
      // Note: we used to assert the following here. This doesn't work because sometimes, during
      // marking/updating-refs, it can happen that a Java thread beats us with an arraycopy,
      // which first copies the array, which potentially contains from-space refs, and only afterwards
      // updates all from-space refs to to-space refs, which leaves a short window where the new array
      // elements can be from-space.
      // assert(oopDesc::is_null(result) ||
      //        oopDesc::unsafe_equals(result, ShenandoahBarrierSet::resolve_oop_static_not_null(result)),
      //       "expect not forwarded");
      return NULL;
    }
  } else {
    shenandoah_assert_not_forwarded(p, heap_oop);
    return heap_oop;
  }
}

inline bool ShenandoahHeap::cancelled_gc() const {
  return _cancelled_gc.is_set();
}

inline bool ShenandoahHeap::try_cancel_gc() {
  return _cancelled_gc.try_set();
}

inline void ShenandoahHeap::clear_cancelled_gc() {
  _cancelled_gc.unset();
  _oom_evac_handler.clear();
}

inline HeapWord* ShenandoahHeap::allocate_from_gclab(Thread* thread, size_t size) {
  assert(UseTLAB, "TLABs should be enabled");

  if (!thread->gclab().is_initialized()) {
    assert(!thread->is_Java_thread() && !thread->is_Worker_thread(),
           err_msg("Performance: thread should have GCLAB: %s", thread->name()));
    // No GCLABs in this thread, fallback to shared allocation
    return NULL;
  }
  HeapWord *obj = thread->gclab().allocate(size);
  if (obj != NULL) {
    return obj;
  }
  // Otherwise...
  return allocate_from_gclab_slow(thread, size);
}

inline oop ShenandoahHeap::evacuate_object(oop p, Thread* thread, bool& evacuated) {
  evacuated = false;

  if (Thread::current()->is_oom_during_evac()) {
    // This thread went through the OOM during evac protocol and it is safe to return
    // the forward pointer. It must not attempt to evacuate any more.
    return ShenandoahBarrierSet::resolve_forwarded(p);
  }

  assert(thread->is_evac_allowed(), "must be enclosed in in oom-evac scope");

  size_t size_no_fwdptr = (size_t) p->size();
  size_t size_with_fwdptr = size_no_fwdptr + BrooksPointer::word_size();

  assert(!heap_region_containing(p)->is_humongous(), "never evacuate humongous objects");

  bool alloc_from_gclab = true;
  HeapWord* filler = NULL;

#ifdef ASSERT
  if (ShenandoahOOMDuringEvacALot &&
      (os::random() & 1) == 0) { // Simulate OOM every ~2nd slow-path call
        filler = NULL;
  } else {
#endif
    if (UseTLAB) {
      filler = allocate_from_gclab(thread, size_with_fwdptr);
    }
    if (filler == NULL) {
      ShenandoahAllocationRequest req = ShenandoahAllocationRequest::for_shared_gc(size_with_fwdptr);
      filler = allocate_memory(req);
      alloc_from_gclab = false;
    }
#ifdef ASSERT
  }
#endif

  if (filler == NULL) {
    control_thread()->handle_alloc_failure_evac(size_with_fwdptr);

    _oom_evac_handler.handle_out_of_memory_during_evacuation();

    return ShenandoahBarrierSet::resolve_forwarded(p);
  }

  // Copy the object and initialize its forwarding ptr:
  HeapWord* copy = filler + BrooksPointer::word_size();
  oop copy_val = oop(copy);

  Copy::aligned_disjoint_words((HeapWord*) p, copy, size_no_fwdptr);
  BrooksPointer::initialize(oop(copy));

  // Try to install the new forwarding pointer.
  oop result = BrooksPointer::try_update_forwardee(p, copy_val);

  if (oopDesc::unsafe_equals(result, p)) {
    // Successfully evacuated. Our copy is now the public one!
    evacuated = true;
    shenandoah_assert_correct(NULL, copy_val);
    return copy_val;
  }  else {
    // Failed to evacuate. We need to deal with the object that is left behind. Since this
    // new allocation is certainly after TAMS, it will be considered live in the next cycle.
    // But if it happens to contain references to evacuated regions, those references would
    // not get updated for this stale copy during this cycle, and we will crash while scanning
    // it the next cycle.
    //
    // For GCLAB allocations, it is enough to rollback the allocation ptr. Either the next
    // object will overwrite this stale copy, or the filler object on LAB retirement will
    // do this. For non-GCLAB allocations, we have no way to retract the allocation, and
    // have to explicitly overwrite the copy with the filler object. With that overwrite,
    // we have to keep the fwdptr initialized and pointing to our (stale) copy.
    if (alloc_from_gclab) {
      thread->gclab().rollback(size_with_fwdptr);
    } else {
      fill_with_object(copy, size_no_fwdptr);
    }
    shenandoah_assert_correct(NULL, copy_val);
    shenandoah_assert_correct(NULL, result);
    return result;
  }
}

inline bool ShenandoahHeap::requires_marking(const void* entry) const {
  return ! _next_marking_context->is_marked(oop(entry));
}

bool ShenandoahHeap::region_in_collection_set(size_t region_index) const {
  assert(collection_set() != NULL, "Sanity");
  return collection_set()->is_in(region_index);
}

bool ShenandoahHeap::in_collection_set(ShenandoahHeapRegion* r) const {
  return region_in_collection_set(r->region_number());
}

template <class T>
inline bool ShenandoahHeap::in_collection_set(T p) const {
  HeapWord* obj = (HeapWord*) p;
  assert(collection_set() != NULL, "Sanity");
  assert(is_in(obj), "should be in heap");

  return collection_set()->is_in(obj);
}

inline bool ShenandoahHeap::is_stable() const {
  return _gc_state.is_clear();
}

inline bool ShenandoahHeap::is_idle() const {
  return _gc_state.is_unset(MARKING | EVACUATION | UPDATEREFS);
}

inline bool ShenandoahHeap::is_concurrent_mark_in_progress() const {
  return _gc_state.is_set(MARKING);
}

inline bool ShenandoahHeap::is_evacuation_in_progress() const {
  return _gc_state.is_set(EVACUATION);
}

inline bool ShenandoahHeap::is_gc_in_progress_mask(uint mask) const {
  return _gc_state.is_set(mask);
}

inline bool ShenandoahHeap::is_degenerated_gc_in_progress() const {
  return _degenerated_gc_in_progress.is_set();
}

inline bool ShenandoahHeap::is_full_gc_in_progress() const {
  return _full_gc_in_progress.is_set();
}

inline bool ShenandoahHeap::is_full_gc_move_in_progress() const {
  return _full_gc_move_in_progress.is_set();
}

inline bool ShenandoahHeap::is_update_refs_in_progress() const {
  return _gc_state.is_set(UPDATEREFS);
}

template<class T>
inline void ShenandoahHeap::marked_object_iterate(ShenandoahHeapRegion* region, T* cl) {
  marked_object_iterate(region, cl, region->top());
}

template<class T>
inline void ShenandoahHeap::marked_object_safe_iterate(ShenandoahHeapRegion* region, T* cl) {
  marked_object_iterate(region, cl, region->concurrent_iteration_safe_limit());
}

template<class T>
inline void ShenandoahHeap::marked_object_iterate(ShenandoahHeapRegion* region, T* cl, HeapWord* limit) {
  assert(BrooksPointer::word_offset() < 0, "skip_delta calculation below assumes the forwarding ptr is before obj");

  ShenandoahMarkingContext* const ctx = complete_marking_context();
  MarkBitMap* mark_bit_map = ctx->mark_bit_map();
  HeapWord* tams = ctx->top_at_mark_start(region->region_number());

  size_t skip_bitmap_delta = BrooksPointer::word_size() + 1;
  size_t skip_objsize_delta = BrooksPointer::word_size() /* + actual obj.size() below */;
  HeapWord* start = region->bottom() + BrooksPointer::word_size();
  HeapWord* end = MIN2(tams + BrooksPointer::word_size(), region->end());

  // Step 1. Scan below the TAMS based on bitmap data.
  HeapWord* limit_bitmap = MIN2(limit, tams);

  // Try to scan the initial candidate. If the candidate is above the TAMS, it would
  // fail the subsequent "< limit_bitmap" checks, and fall through to Step 2.
  HeapWord* cb = mark_bit_map->getNextMarkedWordAddress(start, end);

  intx dist = ShenandoahMarkScanPrefetch;
  if (dist > 0) {
    // Batched scan that prefetches the oop data, anticipating the access to
    // either header, oop field, or forwarding pointer. Not that we cannot
    // touch anything in oop, while it still being prefetched to get enough
    // time for prefetch to work. This is why we try to scan the bitmap linearly,
    // disregarding the object size. However, since we know forwarding pointer
    // preceeds the object, we can skip over it. Once we cannot trust the bitmap,
    // there is no point for prefetching the oop contents, as oop->size() will
    // touch it prematurely.

    // No variable-length arrays in standard C++, have enough slots to fit
    // the prefetch distance.
    static const int SLOT_COUNT = 256;
    guarantee(dist <= SLOT_COUNT, "adjust slot count");
    HeapWord* slots[SLOT_COUNT];

    int avail;
    do {
      avail = 0;
      for (int c = 0; (c < dist) && (cb < limit_bitmap); c++) {
        Prefetch::read(cb, BrooksPointer::byte_offset());
        slots[avail++] = cb;
        cb += skip_bitmap_delta;
        if (cb < limit_bitmap) {
          cb = mark_bit_map->getNextMarkedWordAddress(cb, limit_bitmap);
        }
      }

      for (int c = 0; c < avail; c++) {
        assert (slots[c] < tams,  err_msg("only objects below TAMS here: "  PTR_FORMAT " (" PTR_FORMAT ")", p2i(slots[c]), p2i(tams)));
        assert (slots[c] < limit, err_msg("only objects below limit here: " PTR_FORMAT " (" PTR_FORMAT ")", p2i(slots[c]), p2i(limit)));
        oop obj = oop(slots[c]);
        do_object_marked_complete(cl, obj);
      }
    } while (avail > 0);
  } else {
    while (cb < limit_bitmap) {
      assert (cb < tams,  err_msg("only objects below TAMS here: "  PTR_FORMAT " (" PTR_FORMAT ")", p2i(cb), p2i(tams)));
      assert (cb < limit, err_msg("only objects below limit here: " PTR_FORMAT " (" PTR_FORMAT ")", p2i(cb), p2i(limit)));
      oop obj = oop(cb);
      do_object_marked_complete(cl, obj);
      cb += skip_bitmap_delta;
      if (cb < limit_bitmap) {
        cb = mark_bit_map->getNextMarkedWordAddress(cb, limit_bitmap);
      }
    }
  }

  // Step 2. Accurate size-based traversal, happens past the TAMS.
  // This restarts the scan at TAMS, which makes sure we traverse all objects,
  // regardless of what happened at Step 1.
  HeapWord* cs = tams + BrooksPointer::word_size();
  while (cs < limit) {
    assert (cs > tams,  err_msg("only objects past TAMS here: "   PTR_FORMAT " (" PTR_FORMAT ")", p2i(cs), p2i(tams)));
    assert (cs < limit, err_msg("only objects below limit here: " PTR_FORMAT " (" PTR_FORMAT ")", p2i(cs), p2i(limit)));
    oop obj = oop(cs);
    int size = obj->size();
    do_object_marked_complete(cl, obj);
    cs += size + skip_objsize_delta;
  }
}

template<class T>
inline void ShenandoahHeap::do_object_marked_complete(T* cl, oop obj) {
  assert(!oopDesc::is_null(obj), "sanity");
  assert(obj->is_oop(), "sanity");
  assert(_complete_marking_context->is_marked(obj), "object expected to be marked");
  cl->do_object(obj);
}

template <class T>
class ShenandoahObjectToOopClosure : public ObjectClosure {
  T* _cl;
public:
  ShenandoahObjectToOopClosure(T* cl) : _cl(cl) {}

  void do_object(oop obj) {
    obj->oop_iterate(_cl);
  }
};

template <class T>
class ShenandoahObjectToOopBoundedClosure : public ObjectClosure {
  T* _cl;
  MemRegion _bounds;
public:
  ShenandoahObjectToOopBoundedClosure(T* cl, HeapWord* bottom, HeapWord* top) :
    _cl(cl), _bounds(bottom, top) {}

  void do_object(oop obj) {
    obj->oop_iterate(_cl, _bounds);
  }
};

template<class T>
inline void ShenandoahHeap::marked_object_oop_iterate(ShenandoahHeapRegion* region, T* cl, HeapWord* top) {
  if (region->is_humongous()) {
    HeapWord* bottom = region->bottom();
    if (top > bottom) {
      region = region->humongous_start_region();
      ShenandoahObjectToOopBoundedClosure<T> objs(cl, bottom, top);
      marked_object_iterate(region, &objs);
    }
  } else {
    ShenandoahObjectToOopClosure<T> objs(cl);
    marked_object_iterate(region, &objs, top);
  }
}

template<class T>
inline void ShenandoahHeap::marked_object_oop_iterate(ShenandoahHeapRegion* region, T* cl) {
  marked_object_oop_iterate(region, cl, region->top());
}

template<class T>
inline void ShenandoahHeap::marked_object_oop_safe_iterate(ShenandoahHeapRegion* region, T* cl) {
  marked_object_oop_iterate(region, cl, region->concurrent_iteration_safe_limit());
}

inline ShenandoahHeapRegion* const ShenandoahHeap::get_region(size_t region_idx) const {
  if (region_idx >= _num_regions) {
    return NULL;
  } else {
    return _regions[region_idx];
  }
}

#endif // SHARE_VM_GC_SHENANDOAH_SHENANDOAHHEAP_INLINE_HPP
