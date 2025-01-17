/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See LICENSE in the project root for
 * license information.
 */
package com.microsoft.azure.spring.data.cosmosdb.core.convert;

import com.azure.data.cosmos.CosmosItemProperties;
import com.azure.data.cosmos.internal.Utils;
import com.azure.data.cosmos.internal.query.QueryItem;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.spring.data.cosmosdb.Constants;
import com.microsoft.azure.spring.data.cosmosdb.core.mapping.CosmosPersistentEntity;
import com.microsoft.azure.spring.data.cosmosdb.core.mapping.CosmosPersistentProperty;
import com.microsoft.azure.spring.data.cosmosdb.exception.CosmosDBAccessException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.data.convert.EntityConverter;
import org.springframework.data.mapping.MappingException;
import org.springframework.data.mapping.PersistentPropertyAccessor;
import org.springframework.data.mapping.context.MappingContext;
import org.springframework.data.mapping.model.ConvertingPropertyAccessor;
import org.springframework.util.Assert;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;

import static com.microsoft.azure.spring.data.cosmosdb.Constants.ISO_8601_COMPATIBLE_DATE_PATTERN;

public class MappingCosmosConverter
    implements EntityConverter<CosmosPersistentEntity<?>, CosmosPersistentProperty,
    Object, CosmosItemProperties>,
    ApplicationContextAware {

    protected final MappingContext<? extends CosmosPersistentEntity<?>,
                                          CosmosPersistentProperty> mappingContext;
    protected GenericConversionService conversionService;
    private ApplicationContext applicationContext;
    private ObjectMapper objectMapper;

    public MappingCosmosConverter(
        MappingContext<? extends CosmosPersistentEntity<?>, CosmosPersistentProperty> mappingContext,
        @Qualifier(Constants.OBJECTMAPPER_BEAN_NAME) ObjectMapper objectMapper) {
        this.mappingContext = mappingContext;
        this.conversionService = new GenericConversionService();
        this.objectMapper = objectMapper == null ? ObjectMapperFactory.getObjectMapper() :
            objectMapper;

        // CosmosDB SDK serializes and deserializes logger, which causes this issue:
        // https://github.com/microsoft/spring-data-cosmosdb/issues/423
        //  This is a temporary fix while CosmosDB fixes this problem.
        Utils.getSimpleObjectMapper().addMixIn(QueryItem.class, QueryItemMixIn.class);
    }

    @Override
    public <R> R read(Class<R> type, CosmosItemProperties cosmosItemProperties) {
        if (cosmosItemProperties == null) {
            return null;
        }

        final CosmosPersistentEntity<?> entity = mappingContext.getPersistentEntity(type);
        Assert.notNull(entity, "Entity is null.");

        return readInternal(entity, type, cosmosItemProperties);
    }

    private <R> R readInternal(final CosmosPersistentEntity<?> entity, Class<R> type,
                               final CosmosItemProperties cosmosItemProperties) {

        try {
            final CosmosPersistentProperty idProperty = entity.getIdProperty();
            final Object idValue = cosmosItemProperties.id();
            final JSONObject jsonObject = new JSONObject(cosmosItemProperties.toJson());

            if (idProperty != null) {
                // Replace the key id to the actual id field name in domain
                jsonObject.remove(Constants.ID_PROPERTY_NAME);
                jsonObject.put(idProperty.getName(), idValue);
            }

            return objectMapper.readValue(jsonObject.toString(), type);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read the source document " + cosmosItemProperties.toJson()
                + "  to target type " + type, e);
        }
    }

    @Override
    @Deprecated
    public void write(Object sourceEntity, CosmosItemProperties document) {
        throw new UnsupportedOperationException("The feature is not implemented yet");
    }

    public CosmosItemProperties writeCosmosItemProperties(Object sourceEntity) {
        if (sourceEntity == null) {
            return null;
        }

        final CosmosPersistentEntity<?> persistentEntity =
            mappingContext.getPersistentEntity(sourceEntity.getClass());

        if (persistentEntity == null) {
            throw new MappingException("no mapping metadata for entity type: " + sourceEntity.getClass().getName());
        }

        final ConvertingPropertyAccessor accessor = getPropertyAccessor(sourceEntity);
        final CosmosPersistentProperty idProperty = persistentEntity.getIdProperty();
        final CosmosItemProperties cosmosItemProperties;

        try {
            cosmosItemProperties =
                new CosmosItemProperties(objectMapper.writeValueAsString(sourceEntity));
        } catch (JsonProcessingException e) {
            throw new CosmosDBAccessException("Failed to map document value.", e);
        }

        if (idProperty != null) {
            final Object value = accessor.getProperty(idProperty);
            final String id = value == null ? null : value.toString();
            cosmosItemProperties.id(id);
        }

        return cosmosItemProperties;
    }

    public ApplicationContext getApplicationContext() {
        return this.applicationContext;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public ConversionService getConversionService() {
        return conversionService;
    }

    public MappingContext<? extends CosmosPersistentEntity<?>, CosmosPersistentProperty> getMappingContext() {
        return mappingContext;
    }


    private ConvertingPropertyAccessor getPropertyAccessor(Object entity) {
        final CosmosPersistentEntity<?> entityInformation =
            mappingContext.getPersistentEntity(entity.getClass());

        Assert.notNull(entityInformation, "EntityInformation should not be null.");
        final PersistentPropertyAccessor accessor = entityInformation.getPropertyAccessor(entity);
        return new ConvertingPropertyAccessor(accessor, conversionService);
    }

    /**
     * Convert a property value to the value stored in CosmosDB
     *
     * @param fromPropertyValue
     * @return
     */
    public static Object toCosmosDbValue(Object fromPropertyValue) {
        if (fromPropertyValue == null) {
            return null;
        }

        // com.microsoft.azure.data.cosmos.JsonSerializable#set(String, T) cannot set values for Date and Enum correctly

        if (fromPropertyValue instanceof Date) {
            fromPropertyValue = ((Date) fromPropertyValue).getTime();
        } else
        if (fromPropertyValue instanceof ZonedDateTime) {
            fromPropertyValue = ((ZonedDateTime) fromPropertyValue)
                                        .format(DateTimeFormatter.ofPattern(ISO_8601_COMPATIBLE_DATE_PATTERN));
        } else if (fromPropertyValue instanceof Enum) {
            fromPropertyValue = fromPropertyValue.toString();
        }

        return fromPropertyValue;
    }

    interface QueryItemMixIn {
        @JsonIgnore
        Logger getLogger();
    }
}
