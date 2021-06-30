/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
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
 */

import java.lang.management.*;
import com.sun.management.OperatingSystemMXBean;

/*
 * @test
 * @bug     8193878
 * @summary On MacOS the OperatingSystem MBean returns "NaN" double value for the SystemCpuLoad attribute
 *          when the interval between the retrieval of the value is short.
 *
 * @run main Test8193878
 */
public class Test8193878 {
    public static void main(String[] argv) throws Exception {
        OperatingSystemMXBean mbean = (com.sun.management.OperatingSystemMXBean)
                ManagementFactory.getOperatingSystemMXBean();
        double cpuLoad;
        for (int i = 0; i < 20; i++)
        {
            cpuLoad = mbean.getSystemCpuLoad();
            if (!Double.isFinite(cpuLoad) || (cpuLoad < 0.0 || cpuLoad > 1.0) && cpuLoad != -1.0) {
                throw new RuntimeException("getSystemCpuLoad() returns " + cpuLoad
                        +  " which is not in the [0.0,1.0] interval");
            }
        }
    }
}
