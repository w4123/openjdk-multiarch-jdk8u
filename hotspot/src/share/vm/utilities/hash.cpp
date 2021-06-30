/*
 * Copyright (c) 2002, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
 */

/*
 * This file has been modified by Azul Systems, Inc. in 2019. These
 * modifications are Copyright (c) 2019 Azul Systems, Inc., and are made
 * available on the same license terms set forth above.
 */

/*
 * Implementation of the Secure Hash Algorithm SHA-256 developed by
 * the National Institute of Standards and Technology along with the
 * National Security Agency.
 */

#include <stdio.h>
#include <string.h>

#include "precompiled.hpp"
#include "utilities/hash.hpp"

#if INCLUDE_CRS

static const uint32_t ITERATION = 64;

// Constants for each round
static const uint32_t ROUND_CONSTS[] = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5,
    0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
    0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
    0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
    0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
    0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3,
    0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
    0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
    0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
};

// initial state value for SHA-256
static const uint32_t initialHashes256[] = {
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
    0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
};

// block size for SHA-256
static const uint32_t blockSize = 64;

static void implCompress(uint8_t buf[], uint32_t ofs, uint32_t *state);
static uint32_t lf_delta0(uint32_t x);
static uint32_t lf_delta1(uint32_t x);
static uint32_t lf_sigma0(uint32_t x);
static uint32_t lf_sigma1(uint32_t x);
static uint32_t lf_ch(uint32_t x, uint32_t y, uint32_t z);
static uint32_t lf_maj(uint32_t x, uint32_t y, uint32_t z);

void sha256(uint8_t *in, uint32_t len, uint8_t *out) {
    // state of this object
    uint32_t state[8];
    uint8_t buffer[blockSize];
    uint32_t i,j;

    uint32_t bytesProcessed = 0;

    // state initialization
    for (i=0; i<8; i++)
        state[i] = initialHashes256[i];

    if (len >= blockSize) {
        uint32_t limit = len - len%blockSize;
        for (; bytesProcessed < limit; bytesProcessed += blockSize) {
            implCompress(in, bytesProcessed, state);
        }
        len -= limit;
    }

    if (len > 0) {
        memcpy(buffer, in + bytesProcessed, len);
        bytesProcessed += len;
    }

    // add padding
    buffer[len] = 0x80;
    len++;
    // add final zero bits and 8 bytes length
    if(len > (blockSize - 8)) {
        memset(buffer + len, 0, blockSize - len);
        implCompress(buffer, 0, state);
        len = 0;
    }
    memset(buffer + len, 0, (blockSize - 8) - len);

    uint32_t bitsProcessedLo = bytesProcessed << 3;
    uint32_t bitsProcessedHi = bytesProcessed >> 29;

    buffer[blockSize - 8] = ((uint32_t)(bitsProcessedHi >> 24) & 0xFF);
    buffer[blockSize - 7] = ((uint32_t)(bitsProcessedHi >> 16) & 0xFF);
    buffer[blockSize - 6] = ((uint32_t)(bitsProcessedHi >> 8)  & 0xFF);
    buffer[blockSize - 5] = ((uint32_t)(bitsProcessedHi) & 0xFF);
    buffer[blockSize - 4] = ((uint32_t)(bitsProcessedLo >> 24) & 0xFF);
    buffer[blockSize - 3] = ((uint32_t)(bitsProcessedLo >> 16) & 0xFF);
    buffer[blockSize - 2] = ((uint32_t)(bitsProcessedLo >> 8)  & 0xFF);
    buffer[blockSize - 1] = ((uint32_t)(bitsProcessedLo) & 0xFF);

    implCompress(buffer, 0, state);

    // convert hash data from state
    for(i=0, j=0; i<8; i++, j+=4) {
        out[j]   = ((state[i] >> 24) & 0xFF);
        out[j+1] = ((state[i] >> 16) & 0xFF);
        out[j+2] = ((state[i] >> 8)  & 0xFF);
        out[j+3] = (state[i]         & 0xFF);
    }
}

/**
 * Process the current block to update the state variable state.
 */
static void implCompress(uint8_t buf[], uint32_t ofs, uint32_t *state) {
    uint32_t W[64];
    uint32_t i, j;
    for(i=0, j=ofs; i<16; i++, j+=4) {
        W[i] = (buf[j] & 0xFF)    << 24;
        W[i] |= (buf[j+1] & 0xFF) << 16;
        W[i] |= (buf[j+2] & 0xFF) << 8;
        W[i] |= (buf[j+3] & 0xFF);
    }
    // The first 16 ints are from the byte stream, compute the rest of
    // the W[]'s
    for (i = 16; i < ITERATION; i++) {
            W[i] = lf_delta1(W[i-2]) + W[i-7] + lf_delta0(W[i-15])
                   + W[i-16];
    }

    uint32_t a = state[0];
    uint32_t b = state[1];
    uint32_t c = state[2];
    uint32_t d = state[3];
    uint32_t e = state[4];
    uint32_t f = state[5];
    uint32_t g = state[6];
    uint32_t h = state[7];

    for (i = 0; i < ITERATION; i++) {
        uint32_t T1 = h + lf_sigma1(e) + lf_ch(e,f,g) + ROUND_CONSTS[i] + W[i];
        uint32_t T2 = lf_sigma0(a) + lf_maj(a,b,c);
        h = g;
        g = f;
        f = e;
        e = d + T1;
        d = c;
        c = b;
        b = a;
        a = T1 + T2;
    }
    state[0] += a;
    state[1] += b;
    state[2] += c;
    state[3] += d;
    state[4] += e;
    state[5] += f;
    state[6] += g;
    state[7] += h;
};

/**
 * logical function ch(x,y,z) as defined in spec:
 * (x and y) xor ((complement x) and z)
 */
static uint32_t lf_ch(uint32_t x, uint32_t y, uint32_t z) {
    return (x & y) ^ ((~x) & z);
}

/**
 * logical function maj(x,y,z) as defined in spec:
 * (x and y) xor (x and z) xor (y and z)
 */
static uint32_t lf_maj(uint32_t x, uint32_t y, uint32_t z) {
    return (x & y) ^ (x & z) ^ (y & z);
}

/**
 * logical function R(x,s) - right shift
 * x right shift for s times
 */
static uint32_t lf_R( uint32_t x, uint32_t s ) {
    return ((uint32_t)x >> s);
}

/**
 * logical function S(x,s) - right rotation
 * x circular right shift for s times
 */
static uint32_t lf_S(uint32_t x, uint32_t s) {
    return ((uint32_t)x >> s)  | (x << (32 - s));
}

/**
 * logical function sigma0(x) - xor of results of right rotations
 */
static uint32_t lf_sigma0(uint32_t x) {
    return lf_S(x, 2) ^ lf_S(x, 13) ^ lf_S(x, 22);
}

/**
 * logical function sigma1(x) - xor of results of right rotations
 */
static uint32_t lf_sigma1(uint32_t x) {
    return lf_S( x, 6 ) ^ lf_S( x, 11 ) ^ lf_S( x, 25 );
}

/**
 * logical function delta0(x) - xor of results of right shifts/rotations
 */
static uint32_t lf_delta0(uint32_t x) {
    return lf_S(x, 7) ^ lf_S(x, 18) ^ lf_R(x, 3);
}

/**
 * logical function delta1(x) - xor of results of right shifts/rotations
 */
static uint32_t lf_delta1(uint32_t x) {
    return lf_S(x, 17) ^ lf_S(x, 19) ^ lf_R(x, 10);
}

#endif // INCLUDE_CRS
