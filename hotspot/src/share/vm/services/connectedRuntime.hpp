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

#ifndef SHARE_VM_SERVICES_CONNECTEDRUNTIME_HPP
#define SHARE_VM_SERVICES_CONNECTEDRUNTIME_HPP

#include "memory/allocation.hpp"
#include "runtime/handles.hpp"
#include "utilities/exceptions.hpp"

class CRSEvent;
class CRSToJavaCallEvent;
class CrsMessage;

class ConnectedRuntime : public AllStatic {
  // indicates the native CRS event is pending to be sent to Java layer
  static volatile bool _should_notify;
  // is set to true when java CRS agent has been instantiated so it's possible
  // to invoke callback methods
  static volatile bool _is_init;

  static Klass *_agent_klass;

  typedef enum { CRS_LOG_LEVEL_TRACE, CRS_LOG_LEVEL_DEBUG, CRS_LOG_LEVEL_INFO, CRS_LOG_LEVEL_WARNING, CRS_LOG_LEVEL_ERROR, CRS_LOG_LEVEL_OFF, CRS_LOG_LEVEL_NOT_SET } LogLevel;

  static LogLevel _log_level;

  static void schedule(CRSEvent *event);
  static void parse_options();
  static void parse_arguments(const char *arguments, bool needs_unlock);
  static void parse_log_level(LogLevel *var, const char *value, int value_len);
public:
  static void init();
  static void engage(TRAPS);
  static void disable(const char *msg, bool need_safepoint);

  static void notify_class_load(instanceKlassHandle ikh, u1 const *hash, uintx hash_length, char const *source, TRAPS);
  static void notify_tojava_call(methodHandle *m);
  static void notify_first_call(JavaThread *thread, Method *m);
  static void notify_metaspace_eviction(InstanceKlass *ik, Array<Method*>* methods);
  static void notify_metaspace_eviction(InstanceKlass *ik) {
    notify_metaspace_eviction(ik, NULL);
  }
  static void notify_metaspace_eviction(Method *m);
  static void notify_thread_exit(Thread *thread);

  static bool should_notify_java();
  static void notify_java(TRAPS);
  static void flush_buffers(bool force, bool and_stop, TRAPS);

  static void assign_trace_id(ClassLoaderData *cld);
  static void assign_trace_id(InstanceKlass *ik);
  static void mark_anonymous(InstanceKlass *ik);

  friend class CrsMessage;
  friend class CRSToJavaCallEvent;
};

extern "C" {
  void JNICALL crs_register_natives(JNIEnv *env, jclass clazz, jclass agent_clazz);
}

typedef jint crs_traceid;
#define CRS_INIT_ID(x) ConnectedRuntime::assign_trace_id(x)
#define DEFINE_CRS_TRACE_ID_FIELD mutable crs_traceid _crs_trace_id
#define DEFINE_CRS_TRACE_ID_METHODS \
  crs_traceid crs_trace_id() const { return _crs_trace_id; } \
  crs_traceid* const crs_trace_id_addr() const { return &_crs_trace_id; } \
  void set_crs_trace_id(crs_traceid id) const { _crs_trace_id = id; }

class TLB;
class CRSThreadLocalData {
  TLB *_buffer;

public:
  CRSThreadLocalData(): _buffer() {}
  TLB* buffer() const { return _buffer; }
  void set_buffer(TLB* buffer) { _buffer = buffer; }
};

#define DEFINE_CRS_THREAD_LOCALS \
  mutable CRSThreadLocalData _crs_thread_locals;
#define DEFINE_CRS_THREAD_LOCAL_ACCESSOR \
  CRSThreadLocalData* crs_thread_locals() const { return &_crs_thread_locals; }

#endif // SHARE_VM_SERVICES_CONNECTEDRUNTIME_HPP
