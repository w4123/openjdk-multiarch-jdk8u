/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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

/*
 * @test
 * @bug 8234466
 * @summary attempt to trigger class loading from the classloader
 * during JAR file verification
 * @library /lib/testlibrary
 * @build jdk.testlibrary.*
 *        CompilerUtils
 *        MultiThreadLoad FooService
 * @run main MultiProviderTest
 * @run main MultiProviderTest sign
 */

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import jdk.testlibrary.JDKToolFinder;
import jdk.testlibrary.JDKToolLauncher;
import jdk.testlibrary.Utils;
import jdk.testlibrary.JarUtils;
import jdk.testlibrary.ProcessTools;
import jdk.testlibrary.OutputAnalyzer;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.util.Arrays.asList;

public class MultiProviderTest {

    private static final String METAINFO = "META-INF/services/FooService";
    private static final String TEST_CLASSPATH = System.getProperty("test.class.path", ".");
    private static final String TEST_CLASSES = System.getProperty("test.classes", ".");
    private static String COMBO_CP = TEST_CLASSPATH + File.pathSeparator;
    private static boolean signJars = false;
    static final int NUM_JARS = 5;


    private static final String KEYSTORE = "keystore.jks";
    private static final String ALIAS = "JavaTest";
    private static final String STOREPASS = "changeit";
    private static final String KEYPASS = "changeit";

    public static void main(String[] args) throws Throwable {
        signJars = args.length >=1 && args[0].equals("sign");
        initialize();
        List<String> cmds = new ArrayList<>();
        cmds.add(JDKToolFinder.getJDKTool("java"));
        cmds.addAll(asList(Utils.getTestJavaOpts()));
        List<String> c = new ArrayList<>();
        c.add("-cp");
        c.add(COMBO_CP);
        c.add("-Djava.util.logging.config.file=" +
                Paths.get(System.getProperty("test.src", "."), "logging.properties").toString());
        c.add("MultiThreadLoad");
        c.add(TEST_CLASSES);
        cmds.addAll(c);

        try {
            OutputAnalyzer outputAnalyzer = ProcessTools.executeCommand(cmds.stream()
                    .filter(t -> !t.isEmpty())
                    .toArray(String[]::new))
                    .shouldHaveExitValue(0);
            System.out.println("Output:" + outputAnalyzer.getOutput());
        } catch (Throwable t) {
            throw new RuntimeException("Unexpected fail.", t);
        }
    }

    public static void initialize() throws Throwable {
        if (signJars) {
            genKey();
        }
        for (int i = 0; i < NUM_JARS; i++) {
            String p = "FooProvider" + i;
            String jarName = "FooProvider" + i + ".jar";
            Path javaPath = Paths.get(p + ".java");
            Path jarPath = Paths.get(p + ".jar");
            String contents = "public class FooProvider" + i + " extends FooService { }";
            Files.write(javaPath, contents.getBytes());
            CompilerUtils.compile(javaPath, Paths.get(System.getProperty("test.classes")), "-cp", TEST_CLASSPATH);
            List<String> files = new ArrayList<>();
            files.add(p);
            createJar(jarPath, p, files);
            if (signJars) {
                signJar(TEST_CLASSES + File.separator + jarName);
            }
            COMBO_CP += TEST_CLASSES + File.separator + jarName + File.pathSeparator;
        }
    }

    private static void createProviderConfig(Path config, String providerName) throws Exception {
        Files.createDirectories(config.getParent());
        Files.write(config, providerName.getBytes(), CREATE);
    }

    private static void createJar(Path jar, String provider, List<String> files) throws Exception {
        Path xdir = Paths.get(provider);
        createProviderConfig(xdir.resolve(METAINFO), provider);

        for (String f : files) {
            Path source = Paths.get(Utils.TEST_CLASSES, f + ".class");
            Path target = xdir.resolve(source.getFileName());
            Files.copy(source, target, REPLACE_EXISTING);
        }
        JarUtils.createJarFile(Paths.get(TEST_CLASSES, jar.getFileName().toString()), xdir, Paths.get("."));
    }

    private static void genKey() throws Throwable {
        String keytool = JDKToolFinder.getJDKTool("keytool");
        Files.deleteIfExists(Paths.get(KEYSTORE));
        ProcessTools.executeCommand(keytool,
                "-J-Duser.language=en",
                "-J-Duser.country=US",
                "-genkey",
                "-keyalg", "rsa",
                "-alias", ALIAS,
                "-keystore", KEYSTORE,
                "-keypass", KEYPASS,
                "-dname", "cn=sample",
                "-storepass", STOREPASS
        ).shouldHaveExitValue(0);
    }


    private static OutputAnalyzer signJar(String jarName) throws Throwable {
        List<String> args = new ArrayList<>();
        args.add("-verbose");
        args.add(jarName);
        args.add(ALIAS);

        return jarsigner(args);
    }

    private static OutputAnalyzer jarsigner(List<String> extra)
            throws Throwable {
        JDKToolLauncher launcher = JDKToolLauncher.createUsingTestJDK("jarsigner")
                .addVMArg("-Duser.language=en")
                .addVMArg("-Duser.country=US")
                .addToolArg("-keystore")
                .addToolArg(KEYSTORE)
                .addToolArg("-storepass")
                .addToolArg(STOREPASS)
                .addToolArg("-keypass")
                .addToolArg(KEYPASS);
        for (String s : extra) {
            if (s.startsWith("-J")) {
                launcher.addVMArg(s.substring(2));
            } else {
                launcher.addToolArg(s);
            }
        }
        return ProcessTools.executeCommand(launcher.getCommand());
    }

}

