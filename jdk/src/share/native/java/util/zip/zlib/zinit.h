// Copyright 2019 Azul Systems, Inc.  All Rights Reserved.
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

#include <zutil.h>

typedef struct {
    int initDone;
//inflate funcs
    int (*inflateInit2_)(z_streamp strm, int windowBits, const char *version, int stream_size);
    int (*inflateSetDictionary)(z_streamp strm, const Bytef * dictionary, uInt dictLenght);
    int (*inflateReset)(z_streamp strm);
    int (*inflateEnd)(z_streamp strm);
    int (*inflate)(z_streamp strm, int flush);
//deflate funcs
    int (*deflateInit2_)(z_streamp strm, int level, int method, int windowBits, int memLevel,
         int strategy, const char *version, int stream_size);
    int (*deflateSetDictionary)(z_streamp strm, const Bytef * dictionary, uInt dictLenght);
    int (*deflateParams)(z_streamp strm, int level, int strategy);
    int (*deflateReset)(z_streamp strm);
    int (*deflateEnd)(z_streamp strm);
    int (*deflate)(z_streamp strm, int flush);
//crc and adler
    uLong (*crc32)(uLong crc, const unsigned char FAR *buf, uInt len);
    uLong (*adler32)(uLong adler, const Bytef * buf, uInt len);

} zlibFuncTypes;

extern zlibFuncTypes * getLibraryFuncs();
