/*
 * Copyright (c) 2013, 2018, Oracle and/or its affiliates. All rights reserved.
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

package vm.runtime.defmeth;

import nsk.share.test.TestBase;
import vm.runtime.defmeth.shared.DefMethTest;
import vm.runtime.defmeth.shared.annotation.Crash;
import vm.runtime.defmeth.shared.annotation.KnownFailure;
import vm.runtime.defmeth.shared.annotation.NotApplicableFor;
import vm.runtime.defmeth.shared.data.*;
import static vm.runtime.defmeth.shared.data.method.body.CallMethod.Invoke.*;
import static vm.runtime.defmeth.shared.data.method.body.CallMethod.IndexbyteOp.*;
import vm.runtime.defmeth.shared.builder.TestBuilder;
import static vm.runtime.defmeth.shared.ExecutionMode.*;

/**
 * Scenarios on private methods in interfaces.
 */
public class PrivateMethodsTest extends DefMethTest {

    public static void main(String[] args) {
        TestBase.runTest(new PrivateMethodsTest(), args);
    }

    /*
     * testPrivateInvokeStatic
     *
     * interface I {
     *           private int privateM() { return 1; }
     *   default public  int m()        { return I.privateM(); } // invokestatic
     * }
     * class C implements I {}
     *
     * TEST: I o = new C(); o.m()I throws IncompatibleClassChangeError
     * TEST: C o = new C(); o.m()I throws IncompatibleClassChangeError
     */
    public void testPrivateInvokeStatic() {
        TestBuilder b = factory.getBuilder();

        Interface I = b.intf("I")
                .defaultMethod("privateM", "()I")
                    .private_().returns(1).build()
                .defaultMethod("m", "()I")
                    .invoke(STATIC, b.intfByName("I"), null, "privateM", "()I", CALLSITE).build()
            .build();

        ConcreteClass C = b.clazz("C").implement(I).build();

        b.test().callSite(I, C, "m", "()I").throws_(IncompatibleClassChangeError.class).done()
         .test().callSite(C, C, "m", "()I").throws_(IncompatibleClassChangeError.class).done()

        .run();
    }

    // call from another default method in the same interface
    /*
     * testPrivateCallSameClass
     *
     * interface I {
     *           private privateM()I { return 1; }
     *   default public int m() { return I.super.privateM(); } // invokespecial
     * }
     * class C implements I {}
     *
     * TEST: { I o = new C(); o.m()I  == 1; }
     * TEST: { C o = new C(); o.m()I  == 1; }
     */
    public void testPrivateCallSameClass() {
        TestBuilder b = factory.getBuilder();

        Interface I = b.intf("I")
                .defaultMethod("privateM", "()I")
                    .private_().returns(1).build()
                .defaultMethod("m", "()I")
                    .invokeSpecial(b.intfByName("I"), "privateM", "()I").build()
            .build();

        ConcreteClass C = b.clazz("C").implement(I).build();

        b.test().callSite(I, C, "m", "()I").returns(1).done()
         .test().callSite(C, C, "m", "()I").returns(1).done()

        .run();
    }

    // doesn't participate in default method analysis
    //   method overriding

    /*
     * testPrivate
     *
     * interface I {
     *   private int m() { return 1; }
     * }
     * class C implements I {}
     *
     * TEST: { I o = new C(); o.m()I throws IllegalAccessError; }
     *                 -mode reflect throws NoSuchMethodException
     * TEST: { C o = new C(); o.m()I throws NoSuchMethodError; }
     */
    public void testPrivate() {
        TestBuilder b = factory.getBuilder();

        Interface I = b.intf("I")
                .defaultMethod("m", "()I")
                    .private_().returns(1).build()
            .build();

        ConcreteClass C = b.clazz("C").implement(I).build();

        Class expectedClass;
        if (factory.getExecutionMode().equals("REFLECTION")) {
            expectedClass = NoSuchMethodException.class;
        } else {
            expectedClass = IllegalAccessError.class;
        }

        b.test().callSite(I, C, "m", "()I").throws_(expectedClass).done()
         .test().callSite(C, C, "m", "()I").throws_(NoSuchMethodError.class).done()

        .run();
    }

    /*
     * testPrivateVsConcrete
     *
     * interface I {
     *   private int m() { return 1; }
     * }
     * class C implements I {
     *   public int m() { return 2; }
     * }
     *
     * TEST: { I o = new C(); o.m()I  == IllegalAccessError; }
     *                 -mode reflect throws NoSuchMethodException
     * TEST: { C o = new C(); o.m()I  == 2; }
     */
    public void testPrivateVsConcrete() {
        TestBuilder b = factory.getBuilder();

        Interface I = b.intf("I")
                .defaultMethod("m", "()I")
                    .private_().returns(1).build()
            .build();

        ConcreteClass C = b.clazz("C").implement(I)
                .concreteMethod("m", "()I").returns(2).build()
            .build();

        Class expectedClass;
        if (factory.getExecutionMode().equals("REFLECTION")) {
            expectedClass = NoSuchMethodException.class;
        } else {
            expectedClass = IllegalAccessError.class;
        }

        b.test().callSite(I, C, "m", "()I").throws_(expectedClass).done()
         .test().callSite(C, C, "m", "()I").returns(2).done()

        .run();
    }

    /*
     * testPublicOverridePrivate
     *
     * interface I {
     *   private int m() { return 1; }
     * }
     * interface J extends I {
     *   default public int m() { return 2; }
     * }
     * class C implements J {}
     *
     * TEST: { I o = new C(); o.m()I throws IllegalAccessError; }
     *                 -mode reflect throws NoSuchMethodException
     * TEST: { J o = new C(); o.m()I  == 2; }
     * TEST: { C o = new C(); o.m()I  == 2; }
     */
    public void testPublicOverridePrivate() {
        TestBuilder b = factory.getBuilder();

        Interface I = b.intf("I")
                .defaultMethod("m", "()I")
                    .private_().returns(1).build()
            .build();

        Interface J = b.intf("J").extend(I)
                .defaultMethod("m", "()I")
                    .returns(2).build()
            .build();

        ConcreteClass C = b.clazz("C").implement(J).build();

        Class expectedClass;
        if (factory.getExecutionMode().equals("REFLECTION")) {
            expectedClass = NoSuchMethodException.class;
        } else {
            expectedClass = IllegalAccessError.class;
        }

        b.test().callSite(I, C, "m", "()I").throws_(expectedClass).done()
         .test().callSite(J, C, "m", "()I").returns(2).done()
         .test().callSite(C, C, "m", "()I").returns(2).done()

        .run();
    }

    /*
     * testPrivateOverrideDefault
     *
     * interface I {
     *   default public int m() { return 1; }
     * }
     * interface J extends I {
     *   private int m() { return 2; }
     * }
     * class C implements J {}
     *
     * TEST: { I o = new C(); o.m()I  == 1; }
     * TEST: { J o = new C(); o.m()I  == IllegalAccessError; } II J.m priv
     * TEST: { C o = new C(); o.m()I  == 1; }
     */
    public void testPrivateOverrideDefault() {
        TestBuilder b = factory.getBuilder();

        Interface I = b.intf("I")
                .defaultMethod("m", "()I")
                    .returns(1).build()
            .build();

        Interface J = b.intf("J").extend(I)
                .defaultMethod("m", "()I")
                    .private_().returns(2).build()
            .build();

        ConcreteClass C = b.clazz("C").implement(J).build();

        b.test().callSite(I, C, "m", "()I").returns(1).done()
         .test().privateCallSite(J, C, "m", "()I").throws_(IllegalAccessError.class).done()
         .test().callSite(C, C, "m", "()I").returns(1).done()

        .run();
    }

    /*
     * testPrivateReabstract
     *
     * interface I {
     *   private int m() { return 1; }
     * }
     * interface J extends I {
     *   abstract public int m();
     * }
     * class C implements J {}
     *
     * TEST: { I o = new C(); o.m()I throws IllegalAccessError; } II I.m
     *                 -mode reflect throws NoSuchMethodException
     * TEST: { J o = new C(); o.m()I throws java/lang/AbstractMethodError; }
     * TEST: { C o = new C(); o.m()I throws java/lang/AbstractMethodError; }
     */
    public void testPrivateReabstract() {
        TestBuilder b = factory.getBuilder();

        Interface I = b.intf("I")
                .defaultMethod("m", "()I")
                    .private_().returns(1).build()
            .build();

        Interface J = b.intf("J").extend(I)
                .abstractMethod("m", "()I").build()
            .build();

        ConcreteClass C = b.clazz("C").implement(J).build();

        Class expectedClass;
        if (factory.getExecutionMode().equals("REFLECTION")) {
            expectedClass = NoSuchMethodException.class;
        } else {
            expectedClass = IllegalAccessError.class;
        }

        b.test().callSite(I, C, "m", "()I").throws_(expectedClass).done()
         .test().callSite(J, C, "m", "()I").throws_(AbstractMethodError.class).done()
         .test().callSite(C, C, "m", "()I").throws_(AbstractMethodError.class).done()

        .run();
    }

    /*
     * testPrivateOverrideAbstract
     *
     * interface I {
     *   abstract public int m();
     * }
     * interface J extends I {
     *   private int m() { return 1; }
     * }
     * class C implements J {}
     *
     * TEST: { I o = new C(); o.m()I throws AbstractMethodError }
     * TEST: { J o = new C(); o.m()I throws IllegalAccessError }
     * TEST: { C o = new C(); o.m()I throws AbstractMethodError }
     */
    public void testPrivateOverrideAbstract() {
        TestBuilder b = factory.getBuilder();

        Interface I = b.intf("I")
                .abstractMethod("m", "()I").build()
            .build();

        Interface J = b.intf("J").extend(I)
                .defaultMethod("m", "()I")
                    .private_().returns(1).build()
            .build();

        ConcreteClass C = b.clazz("C").implement(J).build();

        b.test().callSite(I, C, "m", "()I").throws_(AbstractMethodError.class).done()
         .test().privateCallSite(J, C, "m", "()I").throws_(IllegalAccessError.class).done()
         .test().callSite(C, C, "m", "()I").throws_(AbstractMethodError.class).done()
         .run();
    }

    /*
     * testPrivateInherited
     *
     * interface I {
     *   private int m() { return 1; }
     * }
     * class B implements I {}
     * class C extends B {}
     *
     * TEST: { I o = new C(); o.m()I throws IllegalAccessError } II I.m
     *                 -mode reflect throws NoSuchMethodException
     * TEST: { B o = new C(); o.m()I throws NoSuchMethodError }
     * TEST: { C o = new C(); o.m()I throws NoSuchMethodError }
     */
    public void testPrivateInherited() {
        TestBuilder b = factory.getBuilder();

        Interface I = b.intf("I")
                .defaultMethod("m", "()I")
                    .private_().returns(1).build()
            .build();

        ConcreteClass B = b.clazz("B").implement(I).build();
        ConcreteClass C = b.clazz("C").extend(B).build();

        Class expectedClass;
        if (factory.getExecutionMode().equals("REFLECTION")) {
            expectedClass = NoSuchMethodException.class;
        } else {
            expectedClass = IllegalAccessError.class;
        }

        b.test().callSite(I, C, "m","()I").throws_(expectedClass).done()
         .test().callSite(B, C, "m","()I").throws_(NoSuchMethodError.class).done()
         .test().callSite(C, C, "m","()I").throws_(NoSuchMethodError.class).done()

        .run();

    }

    /*
     * testPrivateVsConcreteInherited
     *
     * interface I {
     *    private int m() { return 1; }
     * }
     * class B {
     *   public int m() { return 2; }
     * }
     * class C extends B implements I {}
     *
     * TEST: { I o = new C(); o.m()I  == throws IllegalAccessError; }
     *                     -mode reflect throws NoSuchMethodException
     * TEST: { B o = new C(); o.m()I  == 2; }
     * TEST: { C o = new C(); o.m()I  == 2; }
     */
    public void testPrivateVsConcreteInherited() {
        TestBuilder b = factory.getBuilder();

        Interface I = b.intf("I")
                .defaultMethod("m", "()I")
                    .private_().returns(1).build()
            .build();

        ConcreteClass B = b.clazz("B")
                .concreteMethod("m", "()I").returns(2).build()
                .build();

        ConcreteClass C = b.clazz("C").extend(B).implement(I).build();

        Class expectedClass;
        if (factory.getExecutionMode().equals("REFLECTION")) {
            expectedClass = NoSuchMethodException.class;
        } else {
            expectedClass = IllegalAccessError.class;
        }

        b.test().callSite(I, C, "m","()I").throws_(expectedClass).done()
         .test().callSite(B, C, "m","()I").returns(2).done()
         .test().callSite(C, C, "m","()I").returns(2).done()

        .run();
    }

    /*
     * testPrivateConflict
     *
     * Conflicting methods
     *
     * interface I {
     *   private int m() { return 1; }
     * }
     * interface J {
     *   default public int m() { return 2; }
     * }
     * class C implements I, J {}
     *
     * TEST: { I o = new C(); o.m()I throws IllegalAccessError; }
     *                 -mode reflect throws NoSuchMethodException
     * TEST: { J o = new C(); o.m()I  == 2; }
     * TEST: { C o = new C(); o.m()I  == 2; }
     */
    public void testPrivateConflict() {
        TestBuilder b = factory.getBuilder();

        Interface I = b.intf("I")
                .defaultMethod("m", "()I").private_().returns(1).build()
            .build();

        Interface J = b.intf("J")
                .defaultMethod("m", "()I").returns(2).build()
            .build();

        ConcreteClass C = b.clazz("C").implement(I,J).build();

        Class expectedClass;
        if (factory.getExecutionMode().equals("REFLECTION")) {
            expectedClass = NoSuchMethodException.class;
        } else {
            expectedClass = IllegalAccessError.class;
        }

        b.test().callSite(I, C, "m", "()I").throws_(expectedClass).done()
         .test().callSite(J, C, "m", "()I").returns(2).done()
         .test().callSite(C, C, "m", "()I").returns(2).done()

        .run();
    }
    /*
     * testPrivateSuperClassMethodNoDefaultMethod
     *
     * interface I {
     *  public int m();
     * }
     *
     * public class A {
     *  private int m() { return 1; }
     * }
     *
     * public class B extends A implements I {}
     *
     * public class C extends B {
     *  public int m() { return 2; }
     * }
     *
     * TEST: { B b = new C(); b.m()I throws IllegalAccessError; }
     */
    public void testPrivateSuperClassMethodNoDefaultMethod() {
        TestBuilder b = factory.getBuilder();

        ConcreteClass A = b.clazz("A")
                .concreteMethod("m", "()I").private_().returns(1).build()
                .build();

        Interface I = b.intf("I")
                .abstractMethod("m", "()I").public_().build()
                .build();

        ConcreteClass B = b.clazz("B").extend(A).implement(I).build();

        ConcreteClass C = b.clazz("C").extend(B)
                .concreteMethod("m", "()I").public_().returns(2).build()
                .build();

        b.test().privateCallSite(B, C, "m", "()I").throws_(IllegalAccessError.class).done()
        .run();

    }

    /*
     * testPrivateSuperClassMethodDefaultMethod
     *
     * interface I {
     *  public default int m() { return 3; }
     * }
     *
     * public class A {
     *  private int m() { return 1; }
     * }
     *
     * public class B extends A implements I {}
     *
     * public class C extends B {
     *  public int m() { return 2; }
     * }
     *
     * TEST: { B b = new C(); b.m()I throws IllegalAccessError; }
     */
    public void testPrivateSuperClassMethodDefaultMethod() {
        TestBuilder b = factory.getBuilder();

        ConcreteClass A = b.clazz("A")
                .concreteMethod("m", "()I").private_().returns(1).build()
                .build();

        Interface I = b.intf("I")
                .defaultMethod("m", "()I").public_().returns(3).build()
                .build();

        ConcreteClass B = b.clazz("B").extend(A).implement(I).build();

        ConcreteClass C = b.clazz("C").extend(B)
                .concreteMethod("m", "()I").public_().returns(2).build()
                .build();

        b.test().privateCallSite(B, C, "m", "()I").throws_(IllegalAccessError.class).done()
        .run();
    }

    /*
     * testPrivateSuperClassMethodDefaultMethodNoOverride
     *
     * interface I {
     *  public default int m() { return 3; }
     * }
     *
     * public class A {
     *  private int m() { return 1; }
     * }
     *
     * public class B extends A implements I {}
     *
     * public class C extends B { }
     *
     * TEST: { B b = new C(); b.m()I throws IllegalAccessError; }
     */
    public void testPrivateSuperClassMethodDefaultMethodNoOverride() {
        TestBuilder b = factory.getBuilder();

        ConcreteClass A = b.clazz("A")
                .concreteMethod("m", "()I").private_().returns(1).build()
                .build();

        Interface I = b.intf("I")
                .defaultMethod("m", "()I").public_().returns(3).build()
                .build();

        ConcreteClass B = b.clazz("B").extend(A).implement(I).build();

        ConcreteClass C = b.clazz("C").extend(B).build();

        b.test().privateCallSite(B, C, "m", "()I").throws_(IllegalAccessError.class).done()
        .run();
    }

}
