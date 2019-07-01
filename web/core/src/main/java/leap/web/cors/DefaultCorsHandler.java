/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package leap.web.cors;

import leap.core.annotation.Inject;
import leap.lang.Strings;
import leap.lang.http.HTTP;
import leap.lang.intercepting.State;
import leap.web.Request;
import leap.web.Response;
import leap.web.action.ActionContext;

import javax.servlet.ServletResponse;
import javax.servlet.ServletResponseWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.util.HashSet;
import java.util.Set;

/**
 * @see <a href="http://www.w3.org/TR/cors/">CORS specification</a>
 */
public class DefaultCorsHandler implements CorsHandler {

    static final Set<String> CORS_RESPONSE_HEADERS   = new HashSet<>();
    static final Set<String> SIMPLE_RESPONSE_HEADERS = new HashSet<>();
    static {
        CORS_RESPONSE_HEADERS.add(RESPONSE_HEADER_ACCESS_CONTROL_ALLOW_CREDENTIALS);
        CORS_RESPONSE_HEADERS.add(RESPONSE_HEADER_ACCESS_CONTROL_ALLOW_HEADERS);
        CORS_RESPONSE_HEADERS.add(RESPONSE_HEADER_ACCESS_CONTROL_ALLOW_METHODS);
        CORS_RESPONSE_HEADERS.add(RESPONSE_HEADER_ACCESS_CONTROL_ALLOW_ORIGIN);
        CORS_RESPONSE_HEADERS.add(RESPONSE_HEADER_ACCESS_CONTROL_EXPOSE_HEADERS);
        CORS_RESPONSE_HEADERS.add(RESPONSE_HEADER_ACCESS_CONTROL_MAX_AGE);

        //see : https://www.w3.org/TR/cors/#list-of-exposed-headers
        SIMPLE_RESPONSE_HEADERS.add("cache-control");
        SIMPLE_RESPONSE_HEADERS.add("content-language");
        SIMPLE_RESPONSE_HEADERS.add("content-type");
        SIMPLE_RESPONSE_HEADERS.add("expires");
        SIMPLE_RESPONSE_HEADERS.add("last-modified");
        SIMPLE_RESPONSE_HEADERS.add("pragma");
    }

    protected @Inject CorsConfig conf;

    @Override
    public boolean isPreflightRequest(Request request) {
        String method = request.getRawMethod();
        if("OPTIONS".equals(method) && request.hasHeader(REQUEST_HEADER_ORIGIN)) {
            return true;
        }
        return false;
    }

    public State preHandle(Request request, Response response) throws Throwable {
		if(!request.hasHeader(REQUEST_HEADER_ORIGIN)) {
			return State.CONTINUE;
		}
		
		//6.1 Simple Cross-Origin Request, Actual Request, and Redirects
		//1. If the Origin header is not present terminate this set of steps. 
		//   The request is outside the scope of this specification.

		//6.2 Preflight Request
		//1. If the Origin header is not present terminate this set of steps. 
		//   The request is outside the scope of this specification.
		
		return doProcess(request, response, conf, request.getHeader(REQUEST_HEADER_ORIGIN));
	}

    protected State doProcess(Request request, Response response, CorsConfig conf, String origin) throws Throwable {
		//HTTP method
		String method = request.getRawMethod();
		
		//Preflight request
		if("OPTIONS".equals(method)) {
			String accessControlRequestMethod = request.getHeader(REQUEST_HEADER_ACCESS_CONTROL_REQUEST_METHOD);
			if(null != accessControlRequestMethod) {
				return doProcessPreflight(request, response, conf, origin, method, accessControlRequestMethod);
			}
		}
		
		//Simple or Actual request.
		return doProcessSimpleOrActual(request, response, conf, origin, method);
	}
	
	protected State doProcessSimpleOrActual(Request request, Response response, CorsConfig conf, 
										    String origin, String method) throws Throwable {
		
		//6.1.1 6.1.2
		//Check the origin is allowed
		if(!checkAndProcessInvalidOrigin(request, response, conf, origin)) {
			return State.INTERCEPTED;
		}
		
		//Check the method is allowed
		if(!conf.isMethodAllowed(method)) {
			responseInvalid(request, response, conf, origin);
			return State.INTERCEPTED;
		}
		
		//Section 6.1.3
        processCorsOrigin(response, origin);
		
		//Section 6.1.4
		/*
		 	If the list of exposed headers is not empty add one or more Access-Control-Expose-Headers headers, 
		 	with as values the header field names given in the list of exposed headers.
		 */
		String exposeHeadersValue = getExposeHeadersValue(request);
		if(!Strings.isEmpty(exposeHeadersValue)) {
			response.addHeader(RESPONSE_HEADER_ACCESS_CONTROL_EXPOSE_HEADERS, getExposeHeadersValue(request));
		}else if(conf.isExposeAnyHeaders()) {
			CorsServletResponseWrapper corsResponseWrapper =
					getCorsServletResponseWrapper(response.getServletResponse());
			if(null != corsResponseWrapper) {
				corsResponseWrapper.setExposeAndHeaders(true);
			}
		}

		return State.CONTINUE;
	}

	protected CorsServletResponseWrapper getCorsServletResponseWrapper(ServletResponse response) {
    	if(response instanceof CorsServletResponseWrapper) {
    		return (CorsServletResponseWrapper)response;
		}else if(response instanceof ServletResponseWrapper) {
    		return getCorsServletResponseWrapper(((ServletResponseWrapper) response).getResponse());
		}
    	return null;
	}

	protected String getExposeHeadersValue(Request request) {
		String requestExposeHeaders = Strings.join(getRequestExposeHeaders(request), ',');
		String configExposeHeaders = conf.getExposedHeadersValue();

		if(Strings.isEmpty(configExposeHeaders)) {
			return requestExposeHeaders;
		}else if(Strings.isEmpty(requestExposeHeaders)){
			return configExposeHeaders;
		}else {
			return configExposeHeaders + "," + requestExposeHeaders;
		}
	}

	protected String[] getRequestExposeHeaders(Request request) {
		ActionContext actionContext = request.getActionContext();
		if(null != actionContext && null != actionContext.getRoute()
				&& null != actionContext.getRoute().getCorsExposeHeaders()) {
			return actionContext.getRoute().getCorsExposeHeaders();
		}

		return (String[])request.getAttribute(CorsHandler.REQUEST_ATTRIBUTE_EXPOSE_HEADERS);
	}
	
	protected State doProcessPreflight(Request request, Response response, CorsConfig conf, String origin, 
									  String method, String accessControlRequestMethod) throws Throwable {

		//6.2.1 6.2.2
		//Check the origin is allowed
		if(!checkAndProcessInvalidOrigin(request, response, conf, origin)) {
			return State.INTERCEPTED;
		}
		
		//6.2.3
		//Check the access-control-request-method is allowed.
		if(accessControlRequestMethod.isEmpty() || !conf.isMethodAllowed(accessControlRequestMethod)) {
			responseInvalid(request, response, conf, origin);
			return State.INTERCEPTED;
		}
		
		//Section 6.2.6
		//Check the access-control-request-headers is allowed.
		String accessControlRequestHeaders = request.getHeader(REQUEST_HEADER_ACCESS_CONTROL_REQUEST_HEADERS);
		if(!conf.isAllowAnyHeader()) {
			if(null != accessControlRequestHeaders && accessControlRequestHeaders.length() > 0) {
				for(String h : Strings.split(accessControlRequestHeaders, ',')){
					if(!conf.isHeaderAllowedIgnoreCase(h)) {
						responseInvalid(request, response, conf, origin);
						return State.INTERCEPTED;
					}
				}
			}
		}
		
		//6.2.7
        processCorsOrigin(response, origin);
		
		//6.2.8
		if(conf.getPreflightMaxAge() > 0) {
			setCorsMaxAge(response,String.valueOf(conf.getPreflightMaxAge()));
		}
		
		//6.2.9
		setCorsMethod(response,accessControlRequestMethod);

		//6.2.10
		if(accessControlRequestHeaders != null && accessControlRequestHeaders.length() > 0) {
			setCorsHeaders(response,accessControlRequestHeaders);
		}
		
		return State.INTERCEPTED;
	}
	
	protected boolean checkAndProcessInvalidOrigin(Request request, Response response, CorsConfig conf, String origin) throws Throwable {
		if(null == origin || origin.isEmpty()) {
			responseInvalid(request, response, conf, origin);
			return false;
		}
		
		if(!conf.isOriginAllowed(origin)) {
			responseInvalid(request, response, conf, origin);
			return false;
		}
		
		return true;
	}
	
	protected void responseInvalid(Request request, Response response, CorsConfig conf, String origin) throws Throwable {
		response.setContentType("text/plain");
		response.setStatus(HTTP.SC_FORBIDDEN);
	}

    protected void processCorsOrigin(Response response, String origin) {
        if(conf.isSupportsCredentials()) {
            setCorsOrigin(response,origin);
            setCorsCredentials(response,"true");
        }else{
            if(conf.isAllowAnyOrigin()) {
                setCorsOrigin(response,"*");
            }else{
                setCorsOrigin(response,origin);
            }
        }
    }

	protected void setCorsOrigin(Response response, String headerValue){
		setHeaderWhenEmpty(response,RESPONSE_HEADER_ACCESS_CONTROL_ALLOW_ORIGIN,headerValue);
	}

	protected void setCorsCredentials(Response response, String headerValue){
		setHeaderWhenEmpty(response,RESPONSE_HEADER_ACCESS_CONTROL_ALLOW_CREDENTIALS,headerValue);
	}

	protected void setCorsMaxAge(Response response, String headerValue){
		setHeaderWhenEmpty(response,RESPONSE_HEADER_ACCESS_CONTROL_MAX_AGE,headerValue);
	}

	protected void setCorsMethod(Response response, String headerValue){
		setHeaderWhenEmpty(response,RESPONSE_HEADER_ACCESS_CONTROL_ALLOW_METHODS,headerValue);
	}

	protected void setCorsHeaders(Response response, String headerValue){
		setHeaderWhenEmpty(response,RESPONSE_HEADER_ACCESS_CONTROL_ALLOW_HEADERS,headerValue);
	}

	protected void setHeaderWhenEmpty(Response response, String headerName, String headerValue){
		if(Strings.isEmpty(response.getServletResponse().getHeader(headerName))){
			response.addHeader(headerName,headerValue);
		}
	}
}

