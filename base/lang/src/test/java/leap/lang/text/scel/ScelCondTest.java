/*
 *  Copyright 2018 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package leap.lang.text.scel;

import leap.junit.TestBase;
import leap.lang.New;
import org.junit.Test;

public class ScelCondTest extends TestBase {

    @Test
    public void testContains() {
        ScelExpr expr = ScelParser.parse("a co 's'");

        assertTrue(expr.test(New.hashMap("a", "ss")));
        assertTrue(expr.test(New.hashMap("a", "s")));
        assertFalse(expr.test(New.hashMap()));
        assertFalse(expr.test(New.hashMap("a", "b")));
    }

    @Test
    public void testStartsWith() {
        ScelExpr expr = ScelParser.parse("a sw 's'");

        assertTrue(expr.test(New.hashMap("a", "s1")));
        assertFalse(expr.test(New.hashMap()));
        assertFalse(expr.test(New.hashMap("a", "bs")));
    }

    @Test
    public void testEndsWith() {
        ScelExpr expr = ScelParser.parse("a ew 's'");

        assertTrue(expr.test(New.hashMap("a", "1s")));
        assertFalse(expr.test(New.hashMap()));
        assertFalse(expr.test(New.hashMap("a", "s1")));
    }

    @Test
    public void testNot() {
        ScelExpr expr = ScelParser.parse("not (a eq 's')");
        assertEquals("not ( a eq 's' )", expr.toString());

        expr = ScelParser.parse("not (a eq 's') and (c eq 1)");
        assertEquals("not ( a eq 's' ) and ( c eq 1 )", expr.toString());
    }

    @Test
    public void testIn() {
        ScelExpr expr = ScelParser.parse("a in 1,2");
        assertEquals("a in 1,2", expr.toString());

        expr = ScelParser.parse("a in 1, 2");
        assertEquals("a in 1, 2", expr.toString());

        expr = ScelParser.parse("a in (1,2)");
        assertEquals("a in (1,2)", expr.toString());

        expr = ScelParser.parse("a in ('1','2')");
        assertEquals("a in ('1','2')", expr.toString());

        expr = ScelParser.parse("a not in (1,2)");
        assertEquals("a not in (1,2)", expr.toString());

        expr = ScelParser.parse("a not in ('1','2')");
        assertEquals("a not in ('1','2')", expr.toString());
    }

    @Test
    public void testIsNot() {
        ScelExpr expr = ScelParser.parse("a is not null");
        assertEquals("a is not null", expr.toString());

        expr = ScelParser.parse("a is null");
        assertEquals("a is null", expr.toString());
    }

    @Test
    public void testFindNameValue() {
        ScelExpr expr = ScelParser.parse("a eq 'b' and c eq 'x'");

        ScelNameValue nv = expr.findNameValue("x");
        assertNull(nv);

        nv = expr.findNameValue("c");
        assertNotNull(nv);
        assertEquals("c", nv.name.literal);
        assertEquals(ScelToken.EQ, nv.op);
        assertEquals("x", nv.value.literal);

        assertNull(expr.findNameValue("C"));
        assertNotNull(expr.findNameValue("C", true));
    }
}
