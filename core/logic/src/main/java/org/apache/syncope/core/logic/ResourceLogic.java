/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.syncope.core.logic;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.Transformer;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.syncope.common.lib.SyncopeClientException;
import org.apache.syncope.common.lib.to.ConnObjectTO;
import org.apache.syncope.common.lib.to.ResourceTO;
import org.apache.syncope.common.lib.types.ClientExceptionType;
import org.apache.syncope.common.lib.types.Entitlement;
import org.apache.syncope.common.lib.types.MappingPurpose;
import org.apache.syncope.common.lib.types.SubjectType;
import org.apache.syncope.core.persistence.api.dao.DuplicateException;
import org.apache.syncope.core.persistence.api.dao.ExternalResourceDAO;
import org.apache.syncope.core.persistence.api.dao.NotFoundException;
import org.apache.syncope.core.persistence.api.dao.GroupDAO;
import org.apache.syncope.core.persistence.api.dao.UserDAO;
import org.apache.syncope.core.persistence.api.entity.AttributableUtils;
import org.apache.syncope.core.persistence.api.entity.AttributableUtilsFactory;
import org.apache.syncope.core.persistence.api.entity.ConnInstance;
import org.apache.syncope.core.persistence.api.entity.ExternalResource;
import org.apache.syncope.core.persistence.api.entity.MappingItem;
import org.apache.syncope.core.persistence.api.entity.Subject;
import org.apache.syncope.core.provisioning.api.Connector;
import org.apache.syncope.core.provisioning.api.ConnectorFactory;
import org.apache.syncope.core.provisioning.api.data.ResourceDataBinder;
import org.apache.syncope.core.misc.ConnObjectUtils;
import org.apache.syncope.core.misc.MappingUtils;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.AttributeUtil;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.Uid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ResourceLogic extends AbstractTransactionalLogic<ResourceTO> {

    @Autowired
    private ExternalResourceDAO resourceDAO;

    @Autowired
    private UserDAO userDAO;

    @Autowired
    private GroupDAO groupDAO;

    @Autowired
    private ResourceDataBinder binder;

    @Autowired
    private ConnObjectUtils connObjectUtils;

    @Autowired
    private ConnectorFactory connFactory;

    @Autowired
    private AttributableUtilsFactory attrUtilsFactory;

    @PreAuthorize("hasRole('" + Entitlement.RESOURCE_CREATE + "')")
    public ResourceTO create(final ResourceTO resourceTO) {
        if (StringUtils.isBlank(resourceTO.getKey())) {
            SyncopeClientException sce = SyncopeClientException.build(ClientExceptionType.RequiredValuesMissing);
            sce.getElements().add("Resource name");
            throw sce;
        }

        if (resourceDAO.find(resourceTO.getKey()) != null) {
            throw new DuplicateException("Resource '" + resourceTO.getKey() + "'");
        }

        ExternalResource resource = null;
        try {
            resource = resourceDAO.save(binder.create(resourceTO));
        } catch (SyncopeClientException e) {
            throw e;
        } catch (Exception e) {
            SyncopeClientException ex = SyncopeClientException.build(ClientExceptionType.InvalidExternalResource);
            ex.getElements().add(e.getMessage());
            throw ex;
        }

        return binder.getResourceTO(resource);
    }

    @PreAuthorize("hasRole('" + Entitlement.RESOURCE_UPDATE + "')")
    public ResourceTO update(final ResourceTO resourceTO) {
        ExternalResource resource = resourceDAO.find(resourceTO.getKey());
        if (resource == null) {
            throw new NotFoundException("Resource '" + resourceTO.getKey() + "'");
        }

        resource = binder.update(resource, resourceTO);
        try {
            resource = resourceDAO.save(resource);
        } catch (SyncopeClientException e) {
            throw e;
        } catch (Exception e) {
            SyncopeClientException ex = SyncopeClientException.build(ClientExceptionType.InvalidExternalResource);
            ex.getElements().add(e.getMessage());
            throw ex;
        }

        return binder.getResourceTO(resource);
    }

    @PreAuthorize("hasRole('" + Entitlement.RESOURCE_DELETE + "')")
    public ResourceTO delete(final String resourceName) {
        ExternalResource resource = resourceDAO.find(resourceName);
        if (resource == null) {
            throw new NotFoundException("Resource '" + resourceName + "'");
        }

        ResourceTO resourceToDelete = binder.getResourceTO(resource);

        resourceDAO.delete(resourceName);

        return resourceToDelete;
    }

    @PreAuthorize("hasRole('" + Entitlement.RESOURCE_READ + "')")
    @Transactional(readOnly = true)
    public ResourceTO read(final String resourceName) {
        ExternalResource resource = resourceDAO.find(resourceName);
        if (resource == null) {
            throw new NotFoundException("Resource '" + resourceName + "'");
        }

        return binder.getResourceTO(resource);
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public List<ResourceTO> list() {
        return CollectionUtils.collect(resourceDAO.findAll(), new Transformer<ExternalResource, ResourceTO>() {

            @Override
            public ResourceTO transform(final ExternalResource input) {
                return binder.getResourceTO(input);
            }
        }, new ArrayList<ResourceTO>());
    }

    @PreAuthorize("hasRole('" + Entitlement.RESOURCE_GETCONNECTOROBJECT + "')")
    @Transactional(readOnly = true)
    public ConnObjectTO getConnectorObject(final String resourceName, final SubjectType type, final Long id) {
        ExternalResource resource = resourceDAO.find(resourceName);
        if (resource == null) {
            throw new NotFoundException("Resource '" + resourceName + "'");
        }

        Subject<?, ?, ?> subject = type == SubjectType.USER
                ? userDAO.find(id)
                : groupDAO.find(id);
        if (subject == null) {
            throw new NotFoundException(type + " " + id);
        }

        final AttributableUtils attrUtils = attrUtilsFactory.getInstance(type.asAttributableType());

        MappingItem accountIdItem = attrUtils.getAccountIdItem(resource);
        if (accountIdItem == null) {
            throw new NotFoundException(
                    "AccountId mapping for " + type + " " + id + " on resource '" + resourceName + "'");
        }
        final String accountIdValue = MappingUtils.getAccountIdValue(
                subject, resource, attrUtils.getAccountIdItem(resource));

        final ObjectClass objectClass = SubjectType.USER == type ? ObjectClass.ACCOUNT : ObjectClass.GROUP;

        final Connector connector = connFactory.getConnector(resource);
        final ConnectorObject connectorObject = connector.getObject(objectClass, new Uid(accountIdValue),
                connector.getOperationOptions(attrUtils.getMappingItems(resource, MappingPurpose.BOTH)));
        if (connectorObject == null) {
            throw new NotFoundException("Object " + accountIdValue + " with class " + objectClass
                    + "not found on resource " + resourceName);
        }

        final Set<Attribute> attributes = connectorObject.getAttributes();
        if (AttributeUtil.find(Uid.NAME, attributes) == null) {
            attributes.add(connectorObject.getUid());
        }
        if (AttributeUtil.find(Name.NAME, attributes) == null) {
            attributes.add(connectorObject.getName());
        }

        return connObjectUtils.getConnObjectTO(connectorObject);
    }

    @PreAuthorize("hasRole('" + Entitlement.CONNECTOR_READ + "')")
    @Transactional(readOnly = true)
    public boolean check(final ResourceTO resourceTO) {
        final ConnInstance connInstance = binder.getConnInstance(resourceTO);

        final Connector connector = connFactory.createConnector(connInstance, connInstance.getConfiguration());

        boolean result;
        try {
            connector.test();
            result = true;
        } catch (Exception e) {
            LOG.error("Test connection failure {}", e);
            result = false;
        }

        return result;
    }

    @Override
    protected ResourceTO resolveReference(final Method method, final Object... args)
            throws UnresolvedReferenceException {

        String key = null;

        if (ArrayUtils.isNotEmpty(args)) {
            for (int i = 0; key == null && i < args.length; i++) {
                if (args[i] instanceof String) {
                    key = (String) args[i];
                } else if (args[i] instanceof ResourceTO) {
                    key = ((ResourceTO) args[i]).getKey();
                }
            }
        }

        if (key != null) {
            try {
                return binder.getResourceTO(resourceDAO.find(key));
            } catch (Throwable ignore) {
                LOG.debug("Unresolved reference", ignore);
                throw new UnresolvedReferenceException(ignore);
            }
        }

        throw new UnresolvedReferenceException();
    }
}
