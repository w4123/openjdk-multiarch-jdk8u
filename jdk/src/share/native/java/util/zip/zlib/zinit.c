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

#ifdef __linux__
#include <pthread.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include "zinit.h"

static pthread_once_t initZlibOnce = PTHREAD_ONCE_INIT;

static zlibFuncTypes zlibFuncPtrs;

static void failZlibUnload(char * error, void * handle)
{
    fprintf (stderr, "zlib loading error: %s\n", error);
    dlclose(handle);
    return;
}

void loadZlib()
{
    void * zlibHandle;
    char * error;
    char * zlibPath = getenv ("JDK_ZLIB_PATH");
    zlibFuncPtrs.initDone = 0;
    if (zlibPath == NULL)
    {
      return; //do nothing
    }

    zlibHandle = dlopen(zlibPath, RTLD_NOW);
    if (!zlibHandle) {
      fprintf(stderr, "can't load zlib at provided path %s\n", zlibPath);
      return;
    }

    zlibFuncPtrs.inflateInit2_ = dlsym(zlibHandle, "inflateInit2_");
    if ((error = dlerror()) != NULL)  {
      failZlibUnload(error, zlibHandle);
      return;
    }

    zlibFuncPtrs.inflateSetDictionary = dlsym(zlibHandle, "inflateSetDictionary");
    if ((error = dlerror()) != NULL)  {
      failZlibUnload(error, zlibHandle);
      return;
    }

    zlibFuncPtrs.inflateReset = dlsym(zlibHandle, "inflateReset");
    if ((error = dlerror()) != NULL)  {
      failZlibUnload(error, zlibHandle);
      return;
    }

    zlibFuncPtrs.inflateEnd = dlsym(zlibHandle, "inflateEnd");
    if ((error = dlerror()) != NULL)  {
      failZlibUnload(error, zlibHandle);
      return;
    }

    zlibFuncPtrs.inflate = dlsym(zlibHandle, "inflate");
    if ((error = dlerror()) != NULL)  {
      failZlibUnload(error, zlibHandle);
      return;
    }

    zlibFuncPtrs.deflateInit2_ = dlsym(zlibHandle, "deflateInit2_");
    if ((error = dlerror()) != NULL)  {
      failZlibUnload(error, zlibHandle);
      return;
    }

    zlibFuncPtrs.deflateSetDictionary = dlsym(zlibHandle, "deflateSetDictionary");
    if ((error = dlerror()) != NULL)  {
      failZlibUnload(error, zlibHandle);
      return;
    }

    zlibFuncPtrs.deflateParams = dlsym(zlibHandle, "deflateParams");
    if ((error = dlerror()) != NULL)  {
      failZlibUnload(error, zlibHandle);
      return;
    }

    zlibFuncPtrs.deflateReset = dlsym(zlibHandle, "deflateReset");
    if ((error = dlerror()) != NULL)  {
      failZlibUnload(error, zlibHandle);
      return;
    }

    zlibFuncPtrs.deflateEnd = dlsym(zlibHandle, "deflateEnd");
    if ((error = dlerror()) != NULL)  {
      failZlibUnload(error, zlibHandle);
      return;
    }

    zlibFuncPtrs.deflate = dlsym(zlibHandle, "deflate");
    if ((error = dlerror()) != NULL)  {
      failZlibUnload(error, zlibHandle);
      return;
    }

    zlibFuncPtrs.crc32 = dlsym(zlibHandle, "crc32");
    if ((error = dlerror()) != NULL)  {
      failZlibUnload(error, zlibHandle);
      return;
    }

    zlibFuncPtrs.adler32 = dlsym(zlibHandle, "adler32");
    if ((error = dlerror()) != NULL)  {
      failZlibUnload(error, zlibHandle);
      return;
    }

    zlibFuncPtrs.initDone = 1;
}

zlibFuncTypes * getLibraryFuncs()
{
    pthread_once(&initZlibOnce, loadZlib);

    if (zlibFuncPtrs.initDone == 1) {
      return &zlibFuncPtrs;
    }

    return NULL;
}
#endif
