/*
 *
 *  * Copyright 2016 the original author or authors.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package leap.web.api.orm;

import leap.core.value.Record;
import leap.core.value.SimpleRecord;
import leap.lang.*;
import leap.lang.convert.Converts;
import leap.lang.text.scel.ScelExpr;
import leap.lang.text.scel.ScelName;
import leap.lang.text.scel.ScelNode;
import leap.lang.text.scel.ScelToken;
import leap.orm.mapping.EntityMapping;
import leap.orm.mapping.FieldMapping;
import leap.orm.mapping.RelationMapping;
import leap.orm.mapping.RelationProperty;
import leap.orm.query.CriteriaQuery;
import leap.orm.query.PageResult;
import leap.web.Params;
import leap.web.api.meta.model.MApiModel;
import leap.web.api.meta.model.MApiProperty;
import leap.web.api.mvc.params.CountOptions;
import leap.web.api.mvc.params.QueryOptions;
import leap.web.api.mvc.params.QueryOptionsBase;
import leap.web.api.query.*;
import leap.web.api.remote.RestQueryListResult;
import leap.web.api.remote.RestResource;
import leap.web.api.remote.RestResourceInvokeException;
import leap.web.exception.BadRequestException;
import leap.web.exception.InternalServerErrorException;

import java.lang.reflect.Array;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class DefaultModelQueryExecutor extends ModelExecutorBase implements ModelQueryExecutor {

    protected final ModelAndMapping     modelAndMapping;
    protected final ModelQueryExtension ex;

    protected String   sqlView;
    protected String[] excludedFields;
    protected boolean  filterByParams = true;

    public DefaultModelQueryExecutor(ModelExecutorContext context) {
        this(context, null);
    }

    public DefaultModelQueryExecutor(ModelExecutorContext context, ModelQueryExtension ex) {
        super(context);
        this.modelAndMapping = new ModelAndMapping(am, em);
        this.ex = null == ex ? ModelQueryExtension.EMPTY : ex;
    }

    @Override
    public ModelQueryExecutor fromSqlView(String sql) {
        this.sqlView = sql;
        return this;
    }

    @Override
    public ModelQueryExecutor selectExclude(String... names) {
        this.excludedFields = names;
        return this;
    }

    @Override
    public ModelQueryExecutor setFilterByParams(boolean filterByParams) {
        this.filterByParams = filterByParams;
        return this;
    }

    @Override
    public QueryOneResult queryOne(Object id, QueryOptionsBase options) {
        if (remoteRest) {
            RestResource restResource = restResourceFactory.createResource(dao.getOrmContext(), em);
            Record       record       = restResource.find(id, options);
            return new QueryOneResult(record);
        }

        ModelExecutionContext context = new DefaultModelExecutionContext(this.context);

        if (null != ex.handler) {
            ex.handler.processQueryOneOptions(context, id, options);
        }

        Record record;

        CriteriaQuery<Record> query = createCriteriaQuery().whereById(id);
        applySelect(query, options, new HashMap<>());

        ex.preQueryOne(context, id, query);
        if (null != ex.handler) {
            ex.handler.preQueryOne(context, id, query);
        }
        record = query.firstOrNull();

        try {
            expandOne(record, options);
        }catch (RestResourceInvokeException e) {
            throw new InternalServerErrorException("Remote expanding error: " + e.getMessage(), e);
        }

        if (null != ex.handler && null != record) {
            ex.handler.postQueryOne(context, id, record);
        }

        Object entity = ex.processQueryOneRecord(context, id, record);

        return new QueryOneResult(record, entity);
    }

    protected void expandOne(Record record, QueryOptionsBase options) {
        if (null != record && null != options) {
            Expand[] expands = options.getResolvedExpands();
            if (expands.length > 0) {
                for (Expand expand : expands) {
                    expand(expand, record);
                }
            }
        }
    }

    @Override
    public QueryListResult queryList(QueryOptions options, Map<String, Object> filters, Consumer<CriteriaQuery> callback) {
        return queryList(options, filters, callback, filterByParams);
    }

    @Override
    public QueryListResult queryList(QueryOptions options, Map<String, Object> filters, Consumer<CriteriaQuery> callback, boolean filterByParams) {
        //todo: review query remote entity.
        if (remoteRest) {
            RestResource restResource = restResourceFactory.createResource(dao.getOrmContext(), em);

            RestQueryListResult<Record> result = restResource.queryList(options);

            return new QueryListResult(result.getList(), result.getCount());
        }

        CriteriaQuery<Record>        query        = createCriteriaQuery();
        Map<String, ModelAndMapping> joinedModels = new HashMap<>();

        return doQueryListResult(query, joinedModels, options, filters, callback, filterByParams);
    }

    protected QueryListResult doQueryListResult(CriteriaQuery<Record> query,
                                                Map<String, ModelAndMapping> joinedModels,
                                                QueryOptions options,
                                                Map<String, Object> filters,
                                                Consumer<CriteriaQuery> callback) {
        return doQueryListResult(query, joinedModels, options, filters, callback, filterByParams);
    }

    protected QueryListResult doQueryListResult(CriteriaQuery<Record> query,
                                                Map<String, ModelAndMapping> joinedModels,
                                                QueryOptions options,
                                                Map<String, Object> filters,
                                                Consumer<CriteriaQuery> callback, boolean filterByParams) {
        ModelExecutionContext context = new DefaultModelExecutionContext(this.context);
        if (null == options) {
            options = new QueryOptions();
        }

        ex.processQueryListOptions(context, options);
        if (null != ex.handler) {
            ex.handler.processQueryListOptions(context, options);
        }

        long         count = -1;
        List<Record> list;

        OrderBy orderBy = options.getResolvedOrderBy();
        if (null != orderBy) {
            applyOrderBy(query, orderBy);
        }

        Join[] joins = options.getResolvedJoins();
        if (null != joins && joins.length > 0) {
            Set<String> relations = new HashSet<>();

            for (Join join : joins) {

                if (relations.contains(join.getRelation().toLowerCase())) {
                    throw new BadRequestException("Duplicated join relation '" + join.getRelation() + "'");
                }

                if (joinedModels.containsKey(join.getAlias().toLowerCase())) {
                    throw new BadRequestException("Duplicated join alias '" + join.getAlias() + "'");
                }

                if (join.getAlias().equalsIgnoreCase(query.alias())) {
                    throw new BadRequestException("Alias '" + query.alias() + "' is reserved, please use another one");
                }

                RelationProperty rp = em.tryGetRelationProperty(join.getRelation());
                if (null == rp) {
                    throw new BadRequestException("No relation '" + join.getRelation() + "' in model '" + am.getName() +
                            " or the relation is not joinable");
                }

                if (rp.isOptional()) {
                    query.leftJoin(rp.getTargetEntityName(), rp.getRelationName(), join.getAlias());
                } else {
                    query.join(rp.getTargetEntityName(), rp.getRelationName(), join.getAlias());
                }

                relations.add(join.getRelation().toLowerCase());

                ModelAndMapping joinedModel = lookupModelAndMapping(rp.getTargetEntityName());
                if (null == joinedModel) {
                    throw new BadRequestException("The joined model '" + rp.getTargetEntityName() + "' of relation '" + join.getRelation() + "' not found");
                }
                joinedModels.put(join.getAlias().toLowerCase(), joinedModel);
            }
        }

        applySelectOrAggregates(query, options, joinedModels);
        applyFilters(context, query, options.getParams(), options, joinedModels, filters, filterByParams);

        if (callback != null) {
            callback.accept(query);
        }

        ex.preQueryList(context, query);
        if (null != ex.handler) {
            ex.handler.preQueryList(context, query);
        }

        PageResult page = query.pageResult(options.getPage(ac.getDefaultPageSize()));
        list = ex.executeQueryList(context, options, query);
        if (null == list) {
            list = page.list();
        }

        if (null != ex.handler) {
            ex.handler.postQueryList(context, list);
        }

        if (!list.isEmpty()) {
            Expand[] expands = ExpandParser.parse(options.getExpand());
            if (expands.length > 0) {

                if (list.size() > ac.getMaxExpand()) {
                    throw new BadRequestException("The result size " + list.size() + " exceed max expand " + ac.getMaxExpand() + ", please decrease your page_size");
                }

                try {
                    for (Expand expand : expands) {
                        expand(expand, list);
                    }
                }catch (RestResourceInvokeException e) {
                    throw new InternalServerErrorException("Remote expanding error: " + e.getMessage(), e);
                }
            }
        }

        if (options.isTotal()) {
            count = query.count();
        }

        Object entity = ex.processQueryListResult(context, page, count, list);

        return new QueryListResult(list, count, entity);
    }

    @Override
    public QueryListResult count(CountOptions options, Consumer<CriteriaQuery> callback) {
        if (remoteRest) {
            RestResource restResource = restResourceFactory.createResource(dao.getOrmContext(), em);
            return new QueryListResult(null, restResource.count(options), null);
        }

        ModelExecutionContext context = new DefaultModelExecutionContext(this.context);

        CriteriaQuery<Record> query = createCriteriaQuery();

        QueryOptions queryOptions = new QueryOptions();
        queryOptions.setFilters(options.getFilters());

        applyFilters(context, query, null, queryOptions, null, null);

        if (callback != null) {
            callback.accept(query);
        }

        long count = query.count();

        return new QueryListResult(null, count, null);
    }

    protected CriteriaQuery<Record> createCriteriaQuery() {
        return dao.createCriteriaQuery(em).fromSqlView(sqlView);
    }

    protected void expand(Expand expand, List<Record> records) {
        if (records == null || records.size() == 0) {
            return;
        }

        String       name = expand.getName();
        MApiProperty ap   = am.tryGetProperty(name);
        if (null == ap) {
            throw new BadRequestException("The expand property '" + name + "' not exists!");
        }

        RelationProperty rp = em.tryGetRelationProperty(name);
        if (null == rp) {
            throw new BadRequestException("Property '" + name + "' cannot be expanded");
        }

        EntityMapping targetEm = dao.getOrmContext().getMetadata().tryGetEntityMapping(rp.getTargetEntityName());
        if (targetEm == null) {
            throw new IllegalStateException("Can't find target entity '" + rp.getTargetEntityName() + "'");
        }

        if (targetEm.isRemoteRest()) {
            expandByRest(expand, records, rp);
        } else {
            expandByDb(expand, records, rp);
        }
    }

    protected void expandByRest(Expand expand, List<Record> records, RelationProperty rp) {
        RelationMapping rm = em.getRelationMapping(rp.getRelationName());
        if (rm.isEmbedded()) {
            expandByRestEmbedded(expand, records, rp, rm);
            return;
        }

        EntityMapping targetEm = this.md.getEntityMapping(rm.getTargetEntityName());

        QueryOptions opts = new QueryOptions();
        opts.setLimit(ac.getExpandLimit() + 1);

        RestResource resource =
                restResourceFactory.createResource(dao.getOrmContext(), targetEm);

        //根据不同类型的关系，计算引用的源字段、被引用字段
        String localFieldName;
        String referredFieldName;

        if (rm.isOneToMany()) {
            RelationMapping inverseRm = this.md.getEntityMapping(rm.getJoinEntityName()).getRelationMapping(rm.getInverseRelationName());
            localFieldName = inverseRm.getJoinFields()[0].getReferencedFieldName();
            referredFieldName = inverseRm.getJoinFields()[0].getLocalFieldName();
        } else if (rm.isManyToMany()) {
            throw new BadRequestException("Unsupported remote entity expand when relation type is many-to-many");
        } else {
            localFieldName = rm.getJoinFields()[0].getLocalFieldName();
            referredFieldName = rm.getJoinFields()[0].getReferencedFieldName();
        }

        //获取所有被引用记录的id
        Set<Object> fks = new HashSet<>();
        for (Record record : records) {
            Object fk = record.get(localFieldName);
            if (fk == null || fks.contains(fk)) {
                continue;
            }
            fks.add(fk);
        }

        StringBuilder filters = new StringBuilder();
        int i=0;
        for (Object fk : fks) {
            if(i > 0) {
                filters.append(',');
            }
            filters.append(fk.toString());
            i++;
        }
        opts.setFilters(Strings.format("{0} in ({1})", referredFieldName, filters.toString()));

        //构造expand时，要返回引用记录的字段
        if (Strings.isNotEmpty(expand.getSelect())) {
            if (expand.getSelect().contains(referredFieldName)) {
                opts.setSelect(expand.getSelect());
            } else {
                opts.setSelect(expand.getSelect() + "," + referredFieldName);
            }
        }
        RestQueryListResult<Map> resultList = resource.queryList(Map.class, opts);
        if (resultList.getCount() > ac.getExpandLimit()) {
            throw new BadRequestException("Expanded records of '" + rp.getName() + "' exceed max limit " + ac.getExpandLimit());
        }

        //根据引用字段值，对所有查询出来的被引用数据，进行分组
        Map<Object, List<Record>> referredRecords = new HashMap<>();
        for (Map<String, Object> referred : resultList.getList()) {
            Object       fkVal          = null;
            List<Record> fieldToValList = null;
            if (rm.isManyToMany()) {
                fkVal = referred.remove(referredFieldName);
                if (fkVal == null) {
                    fkVal = referred.remove(referredFieldName.toUpperCase());
                }
                if (fkVal == null) {
                    fkVal = referred.remove(referredFieldName.toLowerCase());
                }
            } else {
                fkVal = referred.get(referredFieldName);
            }

            if (referredRecords.containsKey(fkVal)) {
                fieldToValList = referredRecords.get(fkVal);
            } else {
                fieldToValList = new ArrayList<>();
                referredRecords.put(fkVal, fieldToValList);
            }
            fieldToValList.add(new SimpleRecord(referred));
        }

        //填充expand指定的属性
        for (Record record : records) {
            Object       fk             = record.get(localFieldName);
            List<Record> fieldToRecords = referredRecords.get(fk);
            if (rp.isMany()) {
                record.put(rp.getName(), null == fieldToRecords ? Collections.emptyList() : fieldToRecords);
            } else {
                if (fieldToRecords != null && fieldToRecords.size() > 0) {
                    record.put(rp.getName(), fieldToRecords.get(0));
                } else {
                    record.put(rp.getName(), null);
                }
            }
        }
    }

    protected void expandByDb(Expand expand, List<Record> records, RelationProperty rp) {
        RelationMapping rm = em.getRelationMapping(rp.getRelationName());
        if (rm.isEmbedded()) {
            expandByDbEmbedded(expand, records, rp, rm);
            return;
        }

        CriteriaQuery<Record> expandQuery = dao.createCriteriaQuery(rp.getTargetEntityName()).limit(ac.getExpandLimit() + 1);

        //根据不同类型的关系，计算引用的源字段、被引用字段
        String localFieldName;
        String referredFieldName;

        if (rm.isOneToMany()) {
            RelationMapping inverseRm = this.md.getEntityMapping(rm.getTargetEntityName()).getRelationMapping(rm.getInverseRelationName());
            localFieldName = inverseRm.getJoinFields()[0].getReferencedFieldName();
            referredFieldName = inverseRm.getJoinFields()[0].getLocalFieldName();
        } else if (rm.isManyToMany()) {
            RelationMapping joinRm = this.md.getEntityMapping(rm.getJoinEntityName()).tryGetRelationMappingOfTargetEntity(em.getEntityName());
            localFieldName = em.getKeyFieldNames()[0];
            referredFieldName = joinRm.getJoinFields()[0].getLocalFieldName();
            expandQuery.join(rm.getJoinEntityName(), "_jt_");
        } else {
            localFieldName = rm.getJoinFields()[0].getLocalFieldName();
            referredFieldName = rm.getJoinFields()[0].getReferencedFieldName();
        }

        String referredFieldAlias = rm.getTargetEntityName() + "_" + referredFieldName;

        //获取所有被引用记录的id
        Set<Object> fks = new HashSet<>();
        for (Record record : records) {
            Object fk = record.get(localFieldName);
            if (fk == null || fks.contains(fk)) {
                continue;
            }
            fks.add(fk);
        }

        if (rm.isManyToMany()) {
            expandQuery.where(Strings.format("_jt_.{0} in :fks", referredFieldName))
                    .param("fks", fks.toArray());
        } else {
            expandQuery.where(Strings.format("{0} in :fks", referredFieldName))
                    .param("fks", fks.toArray());
        }

        //构造expand时，要返回引用记录的字段
        if (Strings.isEmpty(expand.getSelect())) {
            if (rm.isManyToMany()) {
                expandQuery.select("*", Strings.format("_jt_.{0} as {1}", referredFieldName, referredFieldAlias));
            } else {
                referredFieldAlias = referredFieldName;
            }
        } else {
            if (rm.isManyToMany()) {
                applySelect(expandQuery, expand.getSelect(), Strings.format("_jt_.{0} as {1}", referredFieldName, referredFieldAlias));
            } else {
                applySelect(expandQuery, expand.getSelect(), referredFieldName);
                referredFieldAlias = referredFieldName;
            }
        }
        List<Record> resultList = expandQuery.list();
        if (resultList.size() > ac.getExpandLimit()) {
            throw new BadRequestException("Expanded records of '" + rp.getName() + "' exceed max limit " + ac.getExpandLimit());
        }

        //根据引用字段值，对所有查询出来的被引用数据，进行分组
        Map<Object, List<Record>> referredRecords = new HashMap<>();
        for (Record referred : resultList) {

            Object       fkVal;
            List<Record> fieldToValList;

            if (rm.isManyToMany()) {
                fkVal = referred.remove(referredFieldAlias);
                if (fkVal == null) {
                    fkVal = referred.remove(referredFieldAlias.toUpperCase());
                }
                if (fkVal == null) {
                    fkVal = referred.remove(referredFieldAlias.toLowerCase());
                }
            } else {
                fkVal = referred.get(referredFieldAlias);
            }

            if (referredRecords.containsKey(fkVal)) {
                fieldToValList = referredRecords.get(fkVal);
            } else {
                fieldToValList = new ArrayList<>();
                referredRecords.put(fkVal, fieldToValList);
            }
            fieldToValList.add(referred);
        }

        //填充expand指定的属性
        for (Record record : records) {
            Object       fk             = record.get(localFieldName);
            List<Record> fieldToRecords = referredRecords.get(fk);
            if (rp.isMany()) {
                record.put(rp.getName(), null == fieldToRecords ? Collections.emptyList() : fieldToRecords);
            } else {
                if (fieldToRecords != null && fieldToRecords.size() > 0) {
                    record.put(rp.getName(), fieldToRecords.get(0));
                } else {
                    record.put(rp.getName(), null);
                }
            }
        }
    }

    protected void expandByRestEmbedded(Expand expand, List<Record> records, RelationProperty rp, RelationMapping rm) {
        Set<Object> ids = new HashSet<>();
        records.forEach(r -> calcIdsByEmbeddedField(ids, r, rm.getEmbeddedFileName()));
        if (ids.isEmpty()) {
            return;
        }

        EntityMapping targetEm    = md.getEntityMapping(rm.getTargetEntityName());
        String        idFieldName = targetEm.getKeyFieldNames()[0];

        RestResource restResource = restResourceFactory.createResource(dao.getOrmContext(), targetEm);

        List<Map> totalExpanded = new ArrayList<>();
        for (List<Object> partOfIds : split(ids, 50)) {
            String       filter  = idFieldName + " in (" + joinInIds(partOfIds) + ")";
            QueryOptions options = new QueryOptions();
            options.setFilters(filter);
            totalExpanded.addAll(restResource.queryList(Map.class, options).getList());
        }

        Map<Object, Map> totalExpandedMap =
                totalExpanded.stream().collect(Collectors.toMap((r) -> r.get(idFieldName), r -> r));

        for (Record record : records) {
            Object embeddedIds  = record.get(rm.getEmbeddedFileName());
            List   expandedList = new ArrayList();
            if (null != embeddedIds) {
                for (Object embeddedId : Enumerables.of(embeddedIds)) {
                    Map expandedRecord = totalExpandedMap.get(embeddedId);
                    if (null != expandedRecord) {
                        expandedList.add(expandedRecord);
                    }
                }
            }
            record.put(rp.getName(), expandedList);
        }
    }

    protected String joinInIds(List<Object> ids) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                s.append(',');
            }
            s.append("'");
            s.append(ids.get(i));
            s.append("'");
        }
        return s.toString();
    }

    protected List<List<Object>> split(Set<Object> set, int num) {
        List<List<Object>> list = new ArrayList<>();

        int          j        = 0;
        List<Object> itemList = new ArrayList<>();
        list.add(itemList);
        for (Object item : set) {
            if (j == num) {
                j = 0;
                itemList = new ArrayList<>();
                list.add(itemList);
            } else {
                j++;
            }
            itemList.add(item);
        }

        return list;
    }

    protected void expandByDbEmbedded(Expand expand, List<Record> records, RelationProperty rp, RelationMapping rm) {
        //calc target ids
        Set<Object> ids = new HashSet<>();
        records.forEach(r -> calcIdsByEmbeddedField(ids, r, rm.getEmbeddedFileName()));
        if (ids.isEmpty()) {
            return;
        }

        //expand by target ids
        EntityMapping targetEm    = md.getEntityMapping(rm.getTargetEntityName());
        String        idFieldName = targetEm.getKeyFieldNames()[0];

        CriteriaQuery<Record> expandQuery =
                dao.createCriteriaQuery(targetEm).where(idFieldName + " in ?", ids);
        if (!Strings.isEmpty(expand.getSelect())) {
            applySelect(expandQuery, expand.getSelect(), false, idFieldName);
        }

        List<Record> totalExpanded = expandQuery.limit(ac.getExpandLimit() + 1).list();
        if (totalExpanded.size() > ac.getExpandLimit()) {
            throw new BadRequestException("Expanded records of '" + rp.getName() + "' exceed max limit " + ac.getExpandLimit());
        }
        Map<Object, Record> totalExpandedMap =
                totalExpanded.stream().collect(Collectors.toMap((r) -> r.get(idFieldName), r -> r));

        for (Record record : records) {
            Object embeddedIds  = record.get(rm.getEmbeddedFileName());
            List   expandedList = new ArrayList();
            if (null != embeddedIds) {
                for (Object embeddedId : Enumerables.of(embeddedIds)) {
                    Record expandedRecord = totalExpandedMap.get(embeddedId);
                    if (null != expandedRecord) {
                        expandedList.add(expandedRecord);
                    }
                }
            }
            record.put(rp.getName(), expandedList);
        }
    }

    public void calcIdsByEmbeddedField(Set<Object> ids, Record record, String embeddedFieldName) {
        Object embeddedIds = record.get(embeddedFieldName);
        if (null != embeddedIds) {
            Enumerable<Object> enumerable = Enumerables.tryOf(embeddedIds);
            if (null == enumerable) {
                throw new BadRequestException("The embedded ids must be array at field '" + embeddedFieldName + "'");
            }
            for (Object item : enumerable) {
                if (!ids.contains(item)) {
                    ids.add(item);
                }
            }
        }
    }

    /**
     * @param record
     * @param id
     * @param expand
     * @see DefaultModelQueryExecutor#expand(Expand expand, Record... records)
     */
    @Deprecated
    protected void expand(Record record, Object id, Expand expand) {
        String name = expand.getName();

        MApiProperty ap = am.tryGetProperty(name);
        if (null == ap) {
            throw new BadRequestException("The expand property '" + name + "' not exists!");
        }

        //todo : check expandable?

        RelationProperty rp = em.tryGetRelationProperty(name);
        if (null == rp) {
            throw new BadRequestException("Property '" + name + "' cannot be expanded");
        }

        RelationMapping rm = em.getRelationMapping(rp.getRelationName());

        CriteriaQuery expandQuery =
                dao.createCriteriaQuery(rp.getTargetEntityName())
                        .joinById(em.getEntityName(), rm.getInverseRelationName(), "t_" + em.getEntityName(), id);


        if (!Strings.isEmpty(expand.getSelect())) {
            applySelect(expandQuery, expand.getSelect());
        }

        if (rp.isMany()) {
            //todo : limit
            record.put(rp.getName(), expandQuery.list());
        } else {
            record.put(rp.getName(), expandQuery.firstOrNull());
        }
    }

    protected void expand(Expand expand, Record... records) {
        expand(expand, Arrays.asList(records));
    }

    protected void applyOrderBy(CriteriaQuery query, OrderBy orderBy) {
        OrderBy.Item[] items = orderBy.items();

        StringBuilder s = new StringBuilder();

        for (int i = 0; i < items.length; i++) {

            if (i > 0) {
                s.append(',');
            }

            OrderBy.Item item = items[i];

            String name = item.name();

            MApiProperty ap = am.tryGetProperty(name);
            if (null == ap) {
                throw new BadRequestException("Property '" + name + "' not exists in model '" + am.getName() + "'");
            }

            if (ap.isNotSortableExplicitly()) {
                throw new BadRequestException("Property '" + name + "' is not sortable!");
            }
            if (Strings.isNotEmpty(query.alias())) {
                s.append(query.alias() + "." + name);
            } else {
                s.append(name);
            }

            if (!item.isAscending()) {
                s.append(" desc");
            }
        }

        query.orderBy(s.toString());
    }

    protected void applySelect(CriteriaQuery query, String select) {
        if (Strings.equals("*", select)) {
            return;
        }

        EntityMapping em = query.getEntityMapping();

        String[] names = Strings.split(select, ',');

        List<String> fields = new ArrayList<>();

        for (String name : names) {

            FieldMapping p = em.tryGetFieldMapping(name);

            if (null == p) {
                throw new BadRequestException("Property '" + name + "' not exists, check the 'select' query param");
            }

            fields.add(p.getFieldName());
        }

        if (null != excludedFields) {
            for (String name : excludedFields) {
                fields.remove(name);
            }
        }

        query.select(fields.toArray(new String[fields.size()]));
    }

    protected void applySelect(CriteriaQuery query, String select, String... requiredFields) {
        applySelect(query, select, true, requiredFields);
    }

    /**
     * 设置查询字段
     *
     * @param query          查询语句
     * @param select         待查询字段
     * @param requiredFields select中必须包含的字段，如果不包含，则自动添加该字段
     */
    protected void applySelect(CriteriaQuery query, String select, boolean exclude, String... requiredFields) {
        if (Strings.equals("*", select)) {
            return;
        }

        EntityMapping em = query.getEntityMapping();

        String[] names = Strings.split(select, ',');

        List<String> fields = new ArrayList<>();

        for (String name : names) {

            FieldMapping p = em.tryGetFieldMapping(name);

            if (null == p) {
                throw new BadRequestException("Property '" + name + "' not exists, check the 'select' query param");
            }

            fields.add(p.getFieldName());
        }

        if (exclude && null != excludedFields) {
            for (String name : excludedFields) {
                fields.remove(name);
            }
        }
        if (requiredFields != null && requiredFields.length > 0) {
            for (String requiredField : requiredFields) {
                boolean isContain = false;
                for (String field : fields) {
                    if (Strings.equalsIgnoreCase(requiredField, field)) {
                        isContain = true;
                        break;
                    }
                }
                if (isContain) {
                    continue;
                }
                fields.add(requiredField);
            }
        }
        query.select(fields.toArray(new String[fields.size()]));
    }

    protected void applySelect(CriteriaQuery query, QueryOptionsBase options, Map<String, ModelAndMapping> joins) {
        String select = null == options ? null : options.getSelect();

        List<String> fields = new ArrayList<>();

        if (Strings.isEmpty(select) || "*".equals(select)) {
            for (MApiProperty p : am.getProperties()) {
                if (p.isReference()) {
                    continue;
                }
                if (p.isSelectableExplicitly()) {
                    fields.add(p.getName());
                }
            }
        } else {
            String[] names = Strings.split(select, ',');
            for (String name : names) {
                if (name.equals("*")) {
                    for (MApiProperty p : am.getProperties()) {
                        if (p.isReference()) {
                            continue;
                        }
                        if (p.isSelectableExplicitly()) {
                            fields.add(p.getName());
                        }
                    }
                    continue;
                }

                String[] parts = Strings.splitWhitespaces(name);
                if (parts.length > 2) {
                    throw new BadRequestException("Invalid select item '" + name + "'");
                }

                String alias = null;
                if (parts.length == 2) {
                    name = parts[0];
                    alias = parts[1];
                }
                parts = Strings.split(name, '.');
                if (parts.length > 2) {
                    throw new BadRequestException("Invalid select item '" + name + "'");
                }
                if (parts.length == 2) {
                    String joinAlias = parts[0];
                    String joinField = parts[1];

                    ModelAndMapping join = joins.get(joinAlias);
                    if (null == join) {
                        throw new BadRequestException("The join alias '" + joinAlias + "' not exists, check '" + name + "'");
                    }

                    MApiProperty p = join.model.tryGetProperty(joinField);
                    if (null == p) {
                        throw new BadRequestException("Join property '" + name + "' not exists, check the 'select' query param");
                    }

                    if (!p.isSelectableExplicitly()) {
                        throw new BadRequestException("Join Property '" + name + "' is not selectable");
                    }

                    FieldMapping fm = join.mapping.getFieldMapping(p.getName());

                    if (Strings.isEmpty(alias)) {
                        fields.add(joinAlias + "." + fm.getColumnName());
                    } else {
                        fields.add(joinAlias + "." + fm.getColumnName() + " " + alias);
                    }
                } else {
                    if (!Strings.isEmpty(alias)) {
                        throw new BadRequestException("Select item of primary entity does not supports alias, check '" + name + "'");
                    }

                    MApiProperty p = am.tryGetProperty(name);
                    if (null == p) {
                        throw new BadRequestException("Property '" + name + "' not exists, check the 'select' query param");
                    }
                    if (!p.isSelectableExplicitly()) {
                        throw new BadRequestException("Property '" + name + "' is not selectable");
                    }
                    fields.add(p.getName());
                }
            }
        }

        if (null != excludedFields && excludedFields.length > 0) {
            for (String name : excludedFields) {
                fields.remove(name);
            }
        }

        query.select(fields.toArray(new String[fields.size()]));
    }

    protected void applySelectOrAggregates(CriteriaQuery query, QueryOptions options, Map<String, ModelAndMapping> joins) {
        if (Strings.isEmpty(options.getAggregates()) && Strings.isEmpty(options.getGroupBy())) {
            applySelect(query, options, joins);
            return;
        }

        if (!Strings.isEmpty(options.getSelect())) {
            throw new BadRequestException("Can't use 'select' for aggregation or groupby query");
        }

        List<String> select = new ArrayList<>();

        if (!Strings.isEmpty(options.getGroupBy())) {
            if (Strings.isEmpty(options.getAggregates())) {
                throw new BadRequestException("Must use groupby with aggregates");
            }

            String[] names = Strings.split(options.getGroupBy(), ',');
            for (String name : names) {
                MApiProperty p = am.tryGetProperty(name);
                if (null == p) {
                    throw new BadRequestException("Property '" + name + "' not exists, check the 'groupby' param");
                }
                if (!p.isSelectableExplicitly()) {
                    throw new BadRequestException("Property '" + name + "' is not groupable");
                }
                select.add(p.getName());
            }
            query.groupBy(options.getGroupBy());
        }

        Aggregate[] aggregates = AggregateParser.parse(options.getAggregates());
        for (Aggregate aggregate : aggregates) {
            if (aggregate.getField().equals("*")) {
                select.add(aggregate.getFunction() + "(" + aggregate.getField() + ") as " + aggregate.getAlias());
            } else {
                MApiProperty p = am.tryGetProperty(aggregate.getField());
                if (null == p) {
                    throw new BadRequestException("Property '" + aggregate.getField() + "' not exists, check the 'aggregates' param");
                }
                if (!p.isAggregatableExplicitly()) {
                    throw new BadRequestException("Property '" + aggregate.getField() + "' is not aggregatable");
                }
                select.add(aggregate.getFunction() + "(" + query.alias() + "." + aggregate.getField() + ") as " + aggregate.getAlias());
            }
        }

        query.select(select.toArray(new String[select.size()]));
    }

    protected void applyFilters(ModelExecutionContext context, CriteriaQuery query, Params params,
                                QueryOptions options, Map<String, ModelAndMapping> jms, Map<String, Object> fields) {
        applyFilters(context, query, params, options, jms, fields, filterByParams);
    }

    protected void applyFilters(ModelExecutionContext context, CriteriaQuery query, Params params,
                                QueryOptions options, Map<String, ModelAndMapping> jms, Map<String, Object> fields, boolean filterByParams) {
        StringBuilder whereExpr1 = new StringBuilder();
        List<Object>  whereArgs1 = new ArrayList<>();

        if (null != ex.handler) {
            ex.handler.preProcessQueryListWhere(context, options, whereExpr1, whereArgs1);
        }
        ex.preProcessQueryListWhere(context, options, whereExpr1, whereArgs1);

        //view
        if (!Strings.isEmpty(options.getViewId()) && null == ex.handler) {
            throw new BadRequestException("'viewId' not supported");
        }
        if (null != ex.handler) {
            ex.handler.handleQueryListView(context, options.getViewId(), whereExpr1, whereArgs1);
        }

        final WhereBuilder where = new WhereBuilder(whereExpr1, whereArgs1);

        //fields
        if (null != fields && !fields.isEmpty()) {
            where.and((expr) -> {
                int i = 0;
                for (Map.Entry<String, Object> entry : fields.entrySet()) {
                    if (i > 0) {
                        expr.append(" and ");
                    }
                    i++;
                    if (null != entry.getValue() && entry.getValue().getClass().isArray()) {
                        expr.append(query.alias()).append('.').append(entry.getKey()).append(" in ?");
                    } else {
                        expr.append(query.alias()).append('.').append(entry.getKey()).append(" = ?");
                    }
                    expr.arg(entry.getValue());
                }
            });
        }

        if (null != params && filterByParams) {
            where.and((expr) -> {
                for (String name : params.names()) {

                    String alias;
                    int    dotIndex = name.indexOf('.');
                    if (dotIndex > 0) {
                        alias = name.substring(0, dotIndex);
                        name = name.substring(dotIndex + 1);

                        if (null == jms || !jms.containsKey(alias.toLowerCase())) {
                            throw new BadRequestException("Unknown alias '" + alias + "' at param '" + (alias + "." + name) + "'");
                        }
                    } else {
                        alias = query.alias();
                    }

                    ModelAndProp modelAndProp = lookupModelAndProp(jms, alias, name);
                    if (null == modelAndProp.property) {
                        continue;
                    }

                    checkProperty(modelAndProp, name);

                    String value = params.get(name);
                    if (Strings.isEmpty(value)) {
                        continue;
                    }

                    //                    if (!whereArgs.isEmpty()) {
                    //                        whereExpr.append(" and ");
                    //                    }
                    //
                    String[] a = params.getArray(name);
                    if (a.length == 1) {
                        a = Strings.split(a[0], ',');
                    }

                    if (a.length > 1) {
                        applyFieldFilterIn(expr, alias, modelAndProp.field, a);
                    } else {
                        applyFieldFilter(expr, alias, modelAndProp.field, value, "=");
                    }
                }
            });
        }

        //filters
        ScelExpr filters = options.getResolvedFilters();
        if (null != filters) {
            ScelNode[] nodes = filters.nodes();
            if (nodes.length > 0) {
                where.and((expr) -> {
                    for (int i = 0; i < nodes.length; i++) {
                        ScelNode node = nodes[i];

                        if (node.isParen()) {
                            expr.append(node.literal());
                            continue;
                        }

                        if (node.isAnd()) {
                            expr.append(" and ");
                            continue;
                        }

                        if (node.isOr()) {
                            expr.append(" or ");
                            continue;
                        }

                        ScelName nameNode = (ScelName) nodes[i];

                        String    alias = nameNode.alias();
                        String    name  = nameNode.literal();
                        ScelToken op    = nodes[++i].token();
                        String    value = nodes[++i].literal();

                        if (null != alias) {
                            if (null == jms || !jms.containsKey(alias.toLowerCase())) {
                                throw new BadRequestException("Unknown alias '" + alias + "' at property '" + nameNode.toString() + "'");
                            }
                        } else {
                            alias = query.alias();
                        }

                        ModelAndProp modelAndProp = lookupModelAndProp(jms, alias, name);
                        checkProperty(modelAndProp, name);

                        String sqlOperator = toSqlOperator(op);

                        if (op == ScelToken.IS || op == ScelToken.IS_NOT) {
                            expr.append(alias).append('.').append(name).append(' ').append(sqlOperator);
                            continue;
                        }

                        if (op == ScelToken.SW) {
                            value = "%" + value;
                        } else if (op == ScelToken.EW) {
                            value = value + "%";
                        } else if (op == ScelToken.CO) {
                            value = "%" + value + "%";
                        }

                        //env
                        if (value.endsWith("()")) {
                            String valueExpr = "#{env." + value.substring(0, value.length() - 2) + "}";
                            applyFieldFilterExpr(expr, alias, modelAndProp.field, valueExpr, sqlOperator);
                        } else if (op == ScelToken.IN) {
                            applyFieldFilterIn(expr, alias, modelAndProp.field, nodes[i].values());
                        } else {
                            applyFieldFilter(expr, alias, modelAndProp.field, value, sqlOperator);
                        }
                    }
                });
            }
        }

        if (null != ex.handler) {
            ex.handler.postProcessQueryListWhere(context, options, whereExpr1, whereArgs1);
        }
        ex.postProcessQueryListWhere(context, options, whereExpr1, whereArgs1);

        if (whereExpr1.length() > 0) {
            query.where(whereExpr1.toString(), whereArgs1.toArray());
        }
    }

    protected void checkProperty(ModelAndProp modelAndProp, String name) {
        boolean joined = modelAndProp.model != this.am;

        String modelDesc = (joined ? "joined " : "") + "model '" + modelAndProp.model.getName() + "'";

        if (null == modelAndProp.property) {
            throw new BadRequestException("Property '" + name + "' not exists in " + modelDesc);
        }

        if (null == modelAndProp.field) {
            throw new BadRequestException("No mapping field '" + name + "' in " + modelDesc);
        }

        MApiProperty ap = modelAndProp.property;

        if (ap.isNotFilterableExplicitly()) {
            throw new BadRequestException("Property '" + name + "' is not filterable in " + modelDesc);
        }

        if (ap.isReference()) {
            throw new BadRequestException("Relation Property '" + name + "' is not filterable in " + modelDesc);
        }
    }

    protected ModelAndMapping lookupModelAndMapping(String entityName) {
        MApiModel model = amd.getModel(entityName);
        if (null == model) {
            return null;
        }

        EntityMapping mapping = md.getEntityMapping(entityName);
        if (null == mapping) {
            throw new IllegalStateException("Entity mapping '" + entityName + "' should be exists!");
        }

        return new ModelAndMapping(model, mapping);
    }

    protected ModelAndProp lookupModelAndProp(Map<String, ModelAndMapping> joinedModels, String alias, String propertyName) {
        ModelAndMapping modelAndMapping = null;
        if (null != joinedModels) {
            modelAndMapping = joinedModels.get(alias.toLowerCase());
        }

        if (null == modelAndMapping) {
            modelAndMapping = this.modelAndMapping;
        }

        MApiProperty property = modelAndMapping.model.tryGetProperty(propertyName);
        FieldMapping field    = null == property ? null : modelAndMapping.mapping.tryGetFieldMapping(property.getName());

        return new ModelAndProp(modelAndMapping, property, field);
    }

    protected void applyFieldFilter(WhereBuilder.Expr expr, String alias, FieldMapping fm, Object value, String op) {
        expr.append(alias).append('.').append(fm.getFieldName()).append(' ').append(op).append(" ?");
        expr.arg(Converts.convert(value, fm.getJavaType()));
    }

    protected void applyFieldFilterExpr(WhereBuilder.Expr expr, String alias, FieldMapping fm, String filterExpr, String op) {
        expr.append(alias).append('.').append(fm.getFieldName()).append(' ').append(op).append(" ").append(filterExpr);
    }

    protected void applyFieldFilterIn(WhereBuilder.Expr expr, String alias, FieldMapping fm, String[] values) {
        expr.append(alias).append('.').append(fm.getFieldName()).append(' ').append("in").append(" ?");
        expr.arg(Converts.convert(values, Array.newInstance(((FieldMapping) fm).getJavaType(), 0).getClass()));
    }

    protected void applyFieldFilterIn(WhereBuilder.Expr expr, String alias, FieldMapping fm, List<ScelNode> values) {
        expr.append(alias).append('.').append(fm.getFieldName()).append(' ').append("in").append(" ?");

        final Class<?> type = ((FieldMapping) fm).getJavaType();

        Object[] args = new Object[values.size()];
        for (int i = 0; i < args.length; i++) {
            ScelNode value = values.get(i);
            if (ScelToken.NULL == value.token()) {
                args[i] = null;
            } else {
                args[i] = Converts.convert(value.literal(), type);
            }
        }

        expr.arg(args);
    }

    protected String toSqlOperator(ScelToken op) {

        if (op == ScelToken.EQ) {
            return "=";
        }

        if (op == ScelToken.GE) {
            return ">=";
        }

        if (op == ScelToken.LE) {
            return "<=";
        }

        if (op == ScelToken.GT) {
            return ">";
        }

        if (op == ScelToken.LT) {
            return "<";
        }

        if (op == ScelToken.NE) {
            return "<>";
        }

        if (op == ScelToken.NOT) {
            return "not";
        }

        if (op == ScelToken.IN) {
            return "in";
        }

        if (op == ScelToken.LIKE || op == ScelToken.CO || op == ScelToken.SW || op == ScelToken.EW) {
            return "like";
        }

        if (op == ScelToken.IS) {
            return "is null";
        }

        if (op == ScelToken.IS_NOT || op == ScelToken.PR) {
            return "is not null";
        }

        throw new IllegalStateException("Not supported operator '" + op + "'");
    }

    protected static class ModelAndMapping {

        public final MApiModel     model;
        public final EntityMapping mapping;

        public ModelAndMapping(MApiModel model, EntityMapping mapping) {
            this.model = model;
            this.mapping = mapping;
        }
    }

    protected static class ModelAndProp extends ModelAndMapping {
        final MApiProperty property;
        final FieldMapping field;

        public ModelAndProp(ModelAndMapping mm, MApiProperty property, FieldMapping field) {
            super(mm.model, mm.mapping);
            this.property = property;
            this.field = field;
        }
    }

    protected static class WhereBuilder {

        private final StringBuilder where;
        private final List<Object>  args;

        public WhereBuilder(StringBuilder sql, List<Object> args) {
            this.where = sql;
            this.args = args;
        }

        public WhereBuilder and(String expr) {
            return and(expr, Arrays2.EMPTY_OBJECT_ARRAY);
        }

        public WhereBuilder and(String expr, Object... args) {
            if (this.where.length() == 0) {
                this.where.append(expr);
            } else {
                this.where.insert(0, '(')
                        .append(") and (")
                        .append(expr)
                        .append(')');
            }
            addArgs(args);
            return this;
        }

        public WhereBuilder and(Consumer<Expr> func) {
            Expr expr = new Expr();
            func.accept(expr);
            if (!Strings.isBlank(expr.buf)) {
                return and(expr.buf.toString());
            }
            return this;
        }

        public WhereBuilder or(String expr) {
            return or(expr, Arrays2.EMPTY_OBJECT_ARRAY);
        }

        public WhereBuilder or(String expr, Object... args) {
            if (this.where.length() == 0) {
                this.where.append(expr);
            } else {
                this.where.insert(0, '(')
                        .append(" or (")
                        .append(expr)
                        .append(')');
            }
            addArgs(args);
            return this;
        }

        public WhereBuilder or(Consumer<Expr> func) {
            Expr expr = new Expr();
            func.accept(expr);
            if (!Strings.isBlank(expr.buf)) {
                return or(expr.buf.toString());
            }
            return this;
        }

        private void addArgs(Object... args) {
            Collections2.addAll(this.args, args);
        }

        protected class Expr {
            private final StringBuilder buf = new StringBuilder();

            public Expr append(String s) {
                buf.append(s);
                return this;
            }

            public Expr append(char c) {
                buf.append(c);
                return this;
            }

            public Expr arg(Object arg) {
                args.add(arg);
                return this;
            }
        }

    }

}
