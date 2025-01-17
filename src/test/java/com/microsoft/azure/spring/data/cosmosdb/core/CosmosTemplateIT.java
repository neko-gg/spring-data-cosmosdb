/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See LICENSE in the project root for
 * license information.
 */

package com.microsoft.azure.spring.data.cosmosdb.core;

import com.azure.data.cosmos.CosmosClientException;
import com.azure.data.cosmos.PartitionKey;
import com.microsoft.azure.spring.data.cosmosdb.CosmosDbFactory;
import com.microsoft.azure.spring.data.cosmosdb.common.ResponseDiagnosticsTestUtils;
import com.microsoft.azure.spring.data.cosmosdb.config.CosmosDBConfig;
import com.microsoft.azure.spring.data.cosmosdb.core.convert.MappingCosmosConverter;
import com.microsoft.azure.spring.data.cosmosdb.core.mapping.CosmosMappingContext;
import com.microsoft.azure.spring.data.cosmosdb.core.query.CosmosPageRequest;
import com.microsoft.azure.spring.data.cosmosdb.core.query.Criteria;
import com.microsoft.azure.spring.data.cosmosdb.core.query.CriteriaType;
import com.microsoft.azure.spring.data.cosmosdb.core.query.DocumentQuery;
import com.microsoft.azure.spring.data.cosmosdb.domain.Person;
import com.microsoft.azure.spring.data.cosmosdb.exception.CosmosDBAccessException;
import com.microsoft.azure.spring.data.cosmosdb.repository.support.CosmosEntityInformation;
import org.assertj.core.util.Lists;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.domain.EntityScanner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.PropertySource;
import org.springframework.data.annotation.Persistent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static com.microsoft.azure.spring.data.cosmosdb.common.PageTestUtils.validateLastPage;
import static com.microsoft.azure.spring.data.cosmosdb.common.PageTestUtils.validateNonLastPage;
import static com.microsoft.azure.spring.data.cosmosdb.common.TestConstants.ADDRESSES;
import static com.microsoft.azure.spring.data.cosmosdb.common.TestConstants.DB_NAME;
import static com.microsoft.azure.spring.data.cosmosdb.common.TestConstants.FIRST_NAME;
import static com.microsoft.azure.spring.data.cosmosdb.common.TestConstants.HOBBIES;
import static com.microsoft.azure.spring.data.cosmosdb.common.TestConstants.ID_1;
import static com.microsoft.azure.spring.data.cosmosdb.common.TestConstants.ID_2;
import static com.microsoft.azure.spring.data.cosmosdb.common.TestConstants.ID_3;
import static com.microsoft.azure.spring.data.cosmosdb.common.TestConstants.LAST_NAME;
import static com.microsoft.azure.spring.data.cosmosdb.common.TestConstants.NEW_FIRST_NAME;
import static com.microsoft.azure.spring.data.cosmosdb.common.TestConstants.NEW_LAST_NAME;
import static com.microsoft.azure.spring.data.cosmosdb.common.TestConstants.NOT_EXIST_ID;
import static com.microsoft.azure.spring.data.cosmosdb.common.TestConstants.PAGE_SIZE_1;
import static com.microsoft.azure.spring.data.cosmosdb.common.TestConstants.PAGE_SIZE_2;
import static com.microsoft.azure.spring.data.cosmosdb.common.TestConstants.PAGE_SIZE_3;
import static com.microsoft.azure.spring.data.cosmosdb.common.TestConstants.UPDATED_FIRST_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

@RunWith(SpringJUnit4ClassRunner.class)
@PropertySource(value = { "classpath:application.properties" })
public class CosmosTemplateIT {
    private static final Person TEST_PERSON = new Person(ID_1, FIRST_NAME, LAST_NAME, HOBBIES,
            ADDRESSES);

    private static final Person TEST_PERSON_2 = new Person(ID_2,
            NEW_FIRST_NAME,
            NEW_LAST_NAME, HOBBIES, ADDRESSES);

    private static final Person TEST_PERSON_3 = new Person(ID_3,
            NEW_FIRST_NAME,
            NEW_LAST_NAME, HOBBIES, ADDRESSES);

    private static final String PRECONDITION_IS_NOT_MET = "is not met";

    private static final String WRONG_ETAG = "WRONG_ETAG";

    @Value("${cosmosdb.uri}")
    private String cosmosDbUri;
    @Value("${cosmosdb.key}")
    private String cosmosDbKey;
    @Value("${cosmosdb.populateQueryMetrics}")
    private boolean populateQueryMetrics;

    private static CosmosTemplate cosmosTemplate;
    private static CosmosEntityInformation<Person, String> personInfo;
    private static String collectionName;
    private static boolean initialized;
    private static ResponseDiagnosticsTestUtils responseDiagnosticsTestUtils;

    private Person insertedPerson;

    @Autowired
    private ApplicationContext applicationContext;

    @Before
    public void setup() throws ClassNotFoundException {
        if (!initialized) {
            responseDiagnosticsTestUtils = new ResponseDiagnosticsTestUtils();
            final CosmosDBConfig dbConfig = CosmosDBConfig.builder(cosmosDbUri,
                    cosmosDbKey, DB_NAME).build();
            dbConfig.setResponseDiagnosticsProcessor(responseDiagnosticsTestUtils.getResponseDiagnosticsProcessor());
            dbConfig.setPopulateQueryMetrics(populateQueryMetrics);
            final CosmosDbFactory cosmosDbFactory = new CosmosDbFactory(dbConfig);

            final CosmosMappingContext mappingContext = new CosmosMappingContext();
            personInfo = new CosmosEntityInformation<>(Person.class);
            collectionName = personInfo.getCollectionName();

            mappingContext.setInitialEntitySet(new EntityScanner(this.applicationContext).scan(Persistent.class));

            final MappingCosmosConverter cosmosConverter = new MappingCosmosConverter(mappingContext,
                null);
            cosmosTemplate = new CosmosTemplate(cosmosDbFactory, cosmosConverter, DB_NAME);
            cosmosTemplate.createCollectionIfNotExists(personInfo);
            initialized = true;
        }

        insertedPerson = cosmosTemplate.insert(Person.class.getSimpleName(), TEST_PERSON, null);
    }

    @After
    public void cleanup() {
        cosmosTemplate.deleteAll(Person.class.getSimpleName(), Person.class);
    }

    @Test(expected = CosmosDBAccessException.class)
    public void testInsertDuplicateId() {
        cosmosTemplate.insert(Person.class.getSimpleName(), TEST_PERSON,
                new PartitionKey(personInfo.getPartitionKeyFieldValue(TEST_PERSON)));
    }

    @Test
    public void testFindAll() {
        final List<Person> result = cosmosTemplate.findAll(Person.class.getSimpleName(), Person.class);
        assertThat(result.size()).isEqualTo(1);
        assertThat(result.get(0)).isEqualTo(TEST_PERSON);
        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNotNull();
    }

    @Test
    public void testFindById() {
        final Person result = cosmosTemplate.findById(Person.class.getSimpleName(),
                TEST_PERSON.getId(), Person.class);
        assertEquals(result, TEST_PERSON);
        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNotNull();

        final Person nullResult = cosmosTemplate.findById(Person.class.getSimpleName(),
                NOT_EXIST_ID, Person.class);
        assertThat(nullResult).isNull();
    }

    @Test
    public void testFindByMultiIds() {
        cosmosTemplate.insert(TEST_PERSON_2,
                new PartitionKey(personInfo.getPartitionKeyFieldValue(TEST_PERSON_2)));
        cosmosTemplate.insert(TEST_PERSON_3,
                new PartitionKey(personInfo.getPartitionKeyFieldValue(TEST_PERSON_3)));

        final List<Object> ids = Lists.newArrayList(ID_1, ID_2, ID_3);
        final List<Person> result = cosmosTemplate.findByIds(ids, Person.class, collectionName);

        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNotNull();

        final List<Person> expected = Lists.newArrayList(TEST_PERSON, TEST_PERSON_2, TEST_PERSON_3);
        assertThat(result.size()).isEqualTo(expected.size());
        assertThat(result).containsAll(expected);
    }

    @Test
    public void testUpsertNewDocument() {
        // Delete first as was inserted in setup
        cosmosTemplate.deleteById(Person.class.getSimpleName(), TEST_PERSON.getId(),
                new PartitionKey(personInfo.getPartitionKeyFieldValue(TEST_PERSON)));

        final String firstName = NEW_FIRST_NAME + "_" + UUID.randomUUID().toString();
        final Person newPerson = new Person(TEST_PERSON.getId(), firstName,
                NEW_FIRST_NAME, null, null);

        cosmosTemplate.upsert(Person.class.getSimpleName(), newPerson,
                new PartitionKey(personInfo.getPartitionKeyFieldValue(newPerson)));

        assertThat(responseDiagnosticsTestUtils.getCosmosResponseDiagnostics()).isNotNull();
        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNull();

        final List<Person> result = cosmosTemplate.findAll(Person.class);

        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNotNull();

        assertThat(result.size()).isEqualTo(1);
        assertEquals(result.get(0).getFirstName(), firstName);
    }

    @Test
    public void testUpdate() {
        final Person updated = new Person(TEST_PERSON.getId(), UPDATED_FIRST_NAME,
                TEST_PERSON.getLastName(), TEST_PERSON.getHobbies(), TEST_PERSON.getShippingAddresses());
        updated.set_etag(insertedPerson.get_etag());

        cosmosTemplate.upsert(Person.class.getSimpleName(), updated, null);

        assertThat(responseDiagnosticsTestUtils.getCosmosResponseDiagnostics()).isNotNull();
        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNull();

        final Person result = cosmosTemplate.findById(Person.class.getSimpleName(),
                updated.getId(), Person.class);

        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNotNull();

        assertEquals(result, updated);
    }

    @Test
    public void testOptimisticLockWhenUpdatingWithWrongEtag() {
        final Person updated = new Person(TEST_PERSON.getId(), UPDATED_FIRST_NAME,
                TEST_PERSON.getLastName(), TEST_PERSON.getHobbies(), TEST_PERSON.getShippingAddresses());
        updated.set_etag(WRONG_ETAG);

        try {
            cosmosTemplate.upsert(Person.class.getSimpleName(), updated, null);
        } catch (CosmosDBAccessException e) {
            assertThat(e.getCause()).isNotNull();
            final Throwable cosmosClientException = e.getCause().getCause();
            assertThat(cosmosClientException).isInstanceOf(CosmosClientException.class);
            assertThat(cosmosClientException.getMessage()).contains(PRECONDITION_IS_NOT_MET);

            final Person unmodifiedPerson = cosmosTemplate.findById(Person.class.getSimpleName(),
                    TEST_PERSON.getId(), Person.class);
            assertThat(unmodifiedPerson.getFirstName()).isEqualTo(insertedPerson.getFirstName());
            return;
        }

        fail();
    }

    @Test
    public void testDeleteById() {
        cosmosTemplate.insert(TEST_PERSON_2, null);
        assertThat(cosmosTemplate.findAll(Person.class).size()).isEqualTo(2);

        cosmosTemplate.deleteById(Person.class.getSimpleName(), TEST_PERSON.getId(),
                new PartitionKey(personInfo.getPartitionKeyFieldValue(TEST_PERSON)));

        assertThat(responseDiagnosticsTestUtils.getCosmosResponseDiagnostics()).isNotNull();
        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNull();

        final List<Person> result = cosmosTemplate.findAll(Person.class);

        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNotNull();
        assertThat(result.size()).isEqualTo(1);
        assertEquals(result.get(0), TEST_PERSON_2);
    }

    @Test
    public void testCountByCollection() {
        final long prevCount = cosmosTemplate.count(collectionName);
        assertThat(prevCount).isEqualTo(1);

        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNotNull();

        cosmosTemplate.insert(TEST_PERSON_2,
                new PartitionKey(personInfo.getPartitionKeyFieldValue(TEST_PERSON_2)));

        assertThat(responseDiagnosticsTestUtils.getCosmosResponseDiagnostics()).isNotNull();

        final long newCount = cosmosTemplate.count(collectionName);
        assertThat(newCount).isEqualTo(2);

        assertThat(responseDiagnosticsTestUtils.getCosmosResponseDiagnostics()).isNull();
        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNotNull();
    }

    @Test
    public void testCountByQuery() {
        cosmosTemplate.insert(TEST_PERSON_2,
                new PartitionKey(personInfo.getPartitionKeyFieldValue(TEST_PERSON_2)));

        assertThat(responseDiagnosticsTestUtils.getCosmosResponseDiagnostics()).isNotNull();
        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNull();

        final Criteria criteria = Criteria.getInstance(CriteriaType.IS_EQUAL, "firstName",
                Collections.singletonList(TEST_PERSON_2.getFirstName()));
        final DocumentQuery query = new DocumentQuery(criteria);

        final long count = cosmosTemplate.count(query, Person.class, collectionName);
        assertThat(count).isEqualTo(1);

        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNotNull();
    }

    @Test
    public void testFindAllPageableMultiPages() {
        cosmosTemplate.insert(TEST_PERSON_2,
                new PartitionKey(personInfo.getPartitionKeyFieldValue(TEST_PERSON_2)));

        assertThat(responseDiagnosticsTestUtils.getCosmosResponseDiagnostics()).isNotNull();
        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNull();

        final CosmosPageRequest pageRequest = new CosmosPageRequest(0, PAGE_SIZE_1, null);
        final Page<Person> page1 = cosmosTemplate.findAll(pageRequest, Person.class, collectionName);

        assertThat(page1.getContent().size()).isEqualTo(PAGE_SIZE_1);
        validateNonLastPage(page1, PAGE_SIZE_1);

        assertThat(responseDiagnosticsTestUtils.getCosmosResponseDiagnostics()).isNull();
        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNotNull();

        final Page<Person> page2 = cosmosTemplate.findAll(page1.getPageable(), Person.class,
                collectionName);
        assertThat(page2.getContent().size()).isEqualTo(1);
        validateLastPage(page2, PAGE_SIZE_1);

        assertThat(responseDiagnosticsTestUtils.getCosmosResponseDiagnostics()).isNull();
        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNotNull();
    }

    @Test
    public void testPaginationQuery() {
        cosmosTemplate.insert(TEST_PERSON_2,
                new PartitionKey(personInfo.getPartitionKeyFieldValue(TEST_PERSON_2)));

        assertThat(responseDiagnosticsTestUtils.getCosmosResponseDiagnostics()).isNotNull();
        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNull();

        final Criteria criteria = Criteria.getInstance(CriteriaType.IS_EQUAL, "firstName",
                Collections.singletonList(FIRST_NAME));
        final PageRequest pageRequest = new CosmosPageRequest(0, PAGE_SIZE_2, null);
        final DocumentQuery query = new DocumentQuery(criteria).with(pageRequest);

        final Page<Person> page = cosmosTemplate.paginationQuery(query, Person.class, collectionName);
        assertThat(page.getContent().size()).isEqualTo(1);
        validateLastPage(page, PAGE_SIZE_2);

        assertThat(responseDiagnosticsTestUtils.getCosmosResponseDiagnostics()).isNull();
        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNotNull();
    }

    @Test
    public void testFindAllWithPageableAndSort() {
        cosmosTemplate.insert(TEST_PERSON_2,
                new PartitionKey(personInfo.getPartitionKeyFieldValue(TEST_PERSON_2)));
        cosmosTemplate.insert(TEST_PERSON_3,
                new PartitionKey(personInfo.getPartitionKeyFieldValue(TEST_PERSON_3)));

        assertThat(responseDiagnosticsTestUtils.getCosmosResponseDiagnostics()).isNotNull();
        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNull();

        final Sort sort = Sort.by(Sort.Direction.DESC, "firstName");
        final PageRequest pageRequest = CosmosPageRequest.of(0, PAGE_SIZE_3, null, sort);

        final Page<Person> page = cosmosTemplate.findAll(pageRequest, Person.class, collectionName);
        assertThat(page.getContent().size()).isEqualTo(3);
        validateLastPage(page, PAGE_SIZE_3);

        final List<Person> result = page.getContent();
        assertThat(result.get(0).getFirstName()).isEqualTo(NEW_FIRST_NAME);
        assertThat(result.get(1).getFirstName()).isEqualTo(NEW_FIRST_NAME);
        assertThat(result.get(2).getFirstName()).isEqualTo(FIRST_NAME);

        assertThat(responseDiagnosticsTestUtils.getCosmosResponseDiagnostics()).isNull();
        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNotNull();

    }

    @Test
    public void testFindAllWithTwoPagesAndVerifySortOrder() {
        final Person testPerson4 = new Person("id_4", "barney", NEW_LAST_NAME, HOBBIES, ADDRESSES);
        final Person testPerson5 = new Person("id_5", "fred", NEW_LAST_NAME, HOBBIES, ADDRESSES);

        cosmosTemplate.insert(TEST_PERSON_2,
                new PartitionKey(personInfo.getPartitionKeyFieldValue(TEST_PERSON_2)));
        cosmosTemplate.insert(TEST_PERSON_3,
                new PartitionKey(personInfo.getPartitionKeyFieldValue(TEST_PERSON_3)));
        cosmosTemplate.insert(testPerson4,
                new PartitionKey(personInfo.getPartitionKeyFieldValue(testPerson4)));
        cosmosTemplate.insert(testPerson5,
                new PartitionKey(personInfo.getPartitionKeyFieldValue(testPerson5)));

        final Sort sort = Sort.by(Sort.Direction.ASC, "firstName");
        final PageRequest pageRequest = CosmosPageRequest.of(0, PAGE_SIZE_3, null, sort);

        final Page<Person> firstPage = cosmosTemplate.findAll(pageRequest, Person.class,
                collectionName);

        assertThat(firstPage.getContent().size()).isEqualTo(3);
        validateNonLastPage(firstPage, PAGE_SIZE_3);

        final List<Person> firstPageResults = firstPage.getContent();
        assertThat(firstPageResults.get(0).getFirstName()).isEqualTo(testPerson4.getFirstName());
        assertThat(firstPageResults.get(1).getFirstName()).isEqualTo(FIRST_NAME);
        assertThat(firstPageResults.get(2).getFirstName()).isEqualTo(testPerson5.getFirstName());

        final Page<Person> secondPage = cosmosTemplate.findAll(firstPage.getPageable(), Person.class,
                collectionName);

        assertThat(secondPage.getContent().size()).isEqualTo(2);
        validateLastPage(secondPage, PAGE_SIZE_3);

        final List<Person> secondPageResults = secondPage.getContent();
        assertThat(secondPageResults.get(0).getFirstName()).isEqualTo(NEW_FIRST_NAME);
        assertThat(secondPageResults.get(1).getFirstName()).isEqualTo(NEW_FIRST_NAME);
    }
}
