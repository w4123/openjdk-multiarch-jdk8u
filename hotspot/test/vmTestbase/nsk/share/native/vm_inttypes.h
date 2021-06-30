// Copyright 2020 Azul Systems, Inc.  All Rights Reserved.
// DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
//
// This code is free software; you can redistribute it and/or modify it under
// the terms of the GNU General Public License version 2 only, as published by
// the Free Software Foundation.
//
// This code is distributed in the hope that it will be useful, but WITHOUT ANY
// WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
// A PARTICULAR PURPOSE.  See the GNU General Public License version 2 for more
// details (a copy is included in the LICENSE file that accompanied this code).
//
// You should have received a copy of the GNU General Public License version 2
// along with this work; if not, write to the Free Software Foundation, Inc.,
// 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
//
// Please contact Azul Systems, 385 Moffett Park Drive, Suite 115, Sunnyvale,
// CA 94089 USA or visit www.azul.com if you need additional information or
// have any questions.

#ifndef VM_INTTYPES_DEFINED
#define VM_INTTYPES_DEFINED

#if ( defined ( _MSC_VER ) && _MSC_VER <= 1600 ) || ( defined ( __SUNPRO_CC ) && __SUNPRO_CC <= 0x5120 )
#include <stdint.h>

#ifndef __PRIPTR_PREFIX
#  if defined(_LP64)
#    define __PRIPTR_PREFIX  "l"
#  else
#    define __PRIPTR_PREFIX
#  endif
#endif

#ifndef __PRI64_PREFIX
#  if defined (_LP64)
#    define __PRI64_PREFIX "l"
#  else
#    define __PRI64_PREFIX "ll"
#  endif
#endif

#ifndef PRIuPTR
#  define PRIuPTR  __PRIPTR_PREFIX "u"
#endif

#ifndef PRIxPTR
#  define PRIxPTR  __PRIPTR_PREFIX "x"
#endif

#ifndef PRIdPTR
#  define PRIdPTR __PRIPTR_PREFIX "d"
#endif

#ifndef PRId64
#  define PRId64 __PRI64_PREFIX "d"
#endif

#else
#include <inttypes.h>
#endif

#endif
