/*******************************************************************************
 * Copyright (c) 2011, 2012 Oracle and/or its affiliates. All rights reserved.
 * This program and the accompanying materials are made available under the 
 * terms of the Eclipse Public License v1.0 and Eclipse Distribution License v. 1.0 
 * which accompanies this distribution. 
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at 
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * Contributors:
 *      dclarke/tware - initial 
 ******************************************************************************/
package org.eclipse.persistence.jpa.rs;

import java.io.InputStream;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.transform.stream.StreamSource;

import org.eclipse.persistence.internal.helper.ConversionManager;
import org.eclipse.persistence.internal.jpa.rs.metadata.model.Attribute;
import org.eclipse.persistence.internal.jpa.rs.metadata.model.Descriptor;
import org.eclipse.persistence.internal.jpa.rs.metadata.model.Link;
import org.eclipse.persistence.internal.jpa.rs.metadata.model.LinkTemplate;
import org.eclipse.persistence.internal.jpa.rs.metadata.model.Parameter;
import org.eclipse.persistence.internal.jpa.rs.metadata.model.PersistenceUnit;
import org.eclipse.persistence.internal.jpa.rs.metadata.model.Query;
import org.eclipse.persistence.internal.jpa.rs.metadata.model.SessionBeanCall;
import org.eclipse.persistence.jaxb.JAXBContext;
import org.eclipse.persistence.jaxb.JAXBContextFactory;
import org.eclipse.persistence.jaxb.MarshallerProperties;
import org.eclipse.persistence.jaxb.UnmarshallerProperties;
import org.eclipse.persistence.jpa.rs.util.JPARSLogger;
import org.eclipse.persistence.jpa.rs.util.StreamingOutputMarshaller;

/**
 * PersistenceResource 
 * @author tware
 *
 */
@Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
@Consumes({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
@Path("/")
public class PersistenceResource extends AbstractResource {

    @GET
    public Response getContexts(@Context HttpHeaders hh, @Context UriInfo uriInfo) throws JAXBException {
        return getContexts(hh, uriInfo.getBaseUri());
    }

    protected Response getContexts(HttpHeaders hh, URI baseURI) throws JAXBException {
        Set<String> contexts = getPersistenceFactory().getPersistenceContextNames();
        Iterator<String> contextIterator = contexts.iterator();
        List<Link> links = new ArrayList<Link>();
        String mediaType = StreamingOutputMarshaller.mediaType(hh.getAcceptableMediaTypes()).toString();
        while (contextIterator.hasNext()) {
            String context = contextIterator.next();
            links.add(new Link(context, mediaType, baseURI + context + "/metadata"));
        }
        String result = null;
        result = marshallMetadata(links, mediaType);
        return Response.ok(new StreamingOutputMarshaller(null, result, hh.getAcceptableMediaTypes())).build();
    }

    @POST
    @Produces(MediaType.WILDCARD)
    public Response callSessionBean(@Context HttpHeaders hh, @Context UriInfo ui, InputStream is) throws JAXBException, ClassNotFoundException, NamingException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        return callSessionBeanInternal(hh, ui, is);
    }

    @SuppressWarnings("rawtypes")
    protected Response callSessionBeanInternal(HttpHeaders hh, UriInfo ui, InputStream is) throws JAXBException, ClassNotFoundException, NamingException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        SessionBeanCall call = null;
        call = unmarshallSessionBeanCall(is);

        String jndiName = call.getJndiName();
        javax.naming.Context ctx = new InitialContext();
        Object ans = ctx.lookup(jndiName);
        if (ans == null) {
            JPARSLogger.fine("jpars_could_not_find_session_bean", new Object[] { jndiName });
            return Response.status(Status.NOT_FOUND).build();
        }

        PersistenceContext context = null;
        if (call.getContext() != null) {
            context = getPersistenceFactory().get(call.getContext(), ui.getBaseUri(), null);
            if (context == null) {
                JPARSLogger.fine("jpars_could_not_find_persistence_context", new Object[] { call.getContext() });
                return Response.status(Status.NOT_FOUND).build();
            }
        }

        Class[] parameters = new Class[call.getParameters().size()];
        Object[] args = new Object[call.getParameters().size()];
        int i = 0;
        for (Parameter param : call.getParameters()) {
            Class parameterClass = null;
            Object parameterValue = null;
            if (context != null) {
                parameterClass = context.getClass(param.getTypeName());
            }
            if (parameterClass != null) {
                parameterValue = context.unmarshalEntity(param.getTypeName(), hh.getMediaType(), is);
            } else {
                parameterClass = Thread.currentThread().getContextClassLoader().loadClass(param.getTypeName());
                parameterValue = ConversionManager.getDefaultManager().convertObject(param.getValue(), parameterClass);
            }
            parameters[i] = parameterClass;
            args[i] = parameterValue;
            i++;
        }
        Method method = ans.getClass().getMethod(call.getMethodName(), parameters);
        Object returnValue = method.invoke(ans, args);
        return Response.ok(new StreamingOutputMarshaller(null, returnValue, hh.getAcceptableMediaTypes())).build();
    }

    protected String marshallMetadata(Object metadata, String mediaType) throws JAXBException {
        Class<?>[] jaxbClasses = new Class[] { Link.class, Attribute.class, Descriptor.class, LinkTemplate.class, PersistenceUnit.class, Query.class };
        JAXBContext context = (JAXBContext) JAXBContextFactory.createContext(jaxbClasses, null);
        Marshaller marshaller = context.createMarshaller();
        marshaller.setProperty(MarshallerProperties.JSON_INCLUDE_ROOT, Boolean.FALSE);
        marshaller.setProperty(MarshallerProperties.MEDIA_TYPE, mediaType);
        StringWriter writer = new StringWriter();
        marshaller.marshal(metadata, writer);
        return writer.toString();
    }

    protected SessionBeanCall unmarshallSessionBeanCall(InputStream data) throws JAXBException {
        Class<?>[] jaxbClasses = new Class[] { SessionBeanCall.class };
        JAXBContext context = (JAXBContext) JAXBContextFactory.createContext(jaxbClasses, null);
        Unmarshaller unmarshaller = context.createUnmarshaller();
        unmarshaller.setProperty(UnmarshallerProperties.JSON_INCLUDE_ROOT, Boolean.FALSE);
        unmarshaller.setProperty(UnmarshallerProperties.MEDIA_TYPE, MediaType.APPLICATION_JSON);
        StreamSource ss = new StreamSource(data);
        return unmarshaller.unmarshal(ss, SessionBeanCall.class).getValue();
    }
}