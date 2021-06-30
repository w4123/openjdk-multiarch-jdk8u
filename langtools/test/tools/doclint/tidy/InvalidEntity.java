/*
 * @test /nodynamiccopyright/
 * @bug 8004832
 * @summary Add new doclint package
 * @library ..
 * @build DocLintTester
 * @run main DocLintTester -ref InvalidEntity.out InvalidEntity.java
 */

// tidy: Warning: replacing invalid numeric character reference .*

// See
// http://www.w3.org/TR/html4/sgml/entities.html

/**
 * &#01;
 * &#x01;
 * &splodge;
 *
 */
public class InvalidEntity { }
