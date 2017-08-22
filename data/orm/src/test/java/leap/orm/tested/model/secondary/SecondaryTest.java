/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package leap.orm.tested.model.secondary;

import leap.junit.contexual.ContextualIgnore;
import leap.orm.OrmTestCase;
import org.junit.Test;

@ContextualIgnore
public class SecondaryTest extends OrmTestCase {

    @Test
    public void testInsert() {
        String id = insert().getId();

        assertEquals(new Integer(1), db.queryForInteger("select count(*) from primary_table1 where id_ = ?", new Object[]{id}));
        assertEquals(new Integer(1), db.queryForInteger("select count(*) from secondary_table1 where id_ = ?", new Object[]{id}));
    }

    @Test
    public void testDelete() {
        String id = insert().getId();

        SecondaryEntity1.delete(id);
        assertEquals(new Integer(0), db.queryForInteger("select count(*) from primary_table1 where id_ = ?", new Object[]{id}));
        assertEquals(new Integer(0), db.queryForInteger("select count(*) from secondary_table1 where id_ = ?", new Object[]{id}));
    }

    @Test
    public void testDeleteAll() {
        SecondaryEntity1.deleteAll();
        insert();
        insert();
        assertEquals(new Integer(2), db.queryForInteger("select count(*) from primary_table1"));
        assertEquals(new Integer(2), db.queryForInteger("select count(*) from secondary_table1"));

        SecondaryEntity1.deleteAll();
        assertEquals(new Integer(0), db.queryForInteger("select count(*) from primary_table1"));
        assertEquals(new Integer(0), db.queryForInteger("select count(*) from secondary_table1"));
    }

    private SecondaryEntity1 insert() {
        SecondaryEntity1 entity = new SecondaryEntity1();
        entity.setCol1("c1");
        entity.setCol2("c2");
        entity.create();

        return entity;
    }

}
