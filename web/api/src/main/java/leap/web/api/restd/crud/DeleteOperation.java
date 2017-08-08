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

package leap.web.api.restd.crud;

import leap.lang.Strings;
import leap.orm.dao.Dao;
import leap.web.action.ActionParams;
import leap.web.action.FuncActionBuilder;
import leap.web.api.Api;
import leap.web.api.config.ApiConfig;
import leap.web.api.config.ApiConfigurator;
import leap.web.api.meta.ApiMetadata;
import leap.web.api.meta.model.MApiModel;
import leap.web.api.mvc.ApiResponse;
import leap.web.api.mvc.params.DeleteOptions;
import leap.web.api.orm.*;
import leap.web.api.restd.RestdModel;
import leap.web.api.restd.RestdProcessor;
import leap.web.api.restd.RestdContext;
import leap.web.route.RouteBuilder;

/**
 * Delete by id operation.
 */
public class DeleteOperation extends CrudOperation implements RestdProcessor {

    @Override
    public void preProcessModel(ApiConfigurator c, RestdContext context, RestdModel model) {
        if(!context.getConfig().allowDeleteModel(model.getName())) {
            return;
        }

        String verb = "DELETE";
        String path = fullModelPath(c, model) + getIdPath(model);
        if(isOperationExists(context, verb, path)) {
            return;
        }

        Dao               dao    = context.getDao();
        FuncActionBuilder action = new FuncActionBuilder();
        RouteBuilder      route  = rm.createRoute(verb, path);

        action.setName(Strings.lowerCamel("delete", model.getName()));
        action.setFunction((params) -> execute(context.getApi(), dao, model, params));

        addIdArgument(action, model);
        addArgument(action, DeleteOptions.class, "options");
        addNoContentResponse(action, model);

        configure(context, model, action);
        route.setAction(action.build());

        configure(context, model, route);
        c.addDynamicRoute(rm.loadRoute(context.getRoutes(), route));
    }

    protected Object execute(Api api, Dao dao, RestdModel model, ActionParams params) {
        ApiMetadata amd = api.getMetadata();
        MApiModel   am  = amd.getModel(model.getName());

        ModelExecutorContext context = new SimpleModelExecutorContext(api, am, dao, model.getEntityMapping());
        ModelDeleteExecutor executor = mef.newDeleteExecutor(context);

        Object        id      = params.get(0);
        DeleteOptions options = params.get(1);

        if(executor.deleteOne(id, options).success) {
            return ApiResponse.NO_CONTENT;
        }else{
            return ApiResponse.NOT_FOUND;
        }
    }
}
