/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See LICENSE in the project root for
 * license information.
 */
package com.microsoft.azure.spring.data.cosmosdb.core;

import com.azure.data.cosmos.CosmosKeyCredential;
import com.azure.data.cosmos.PartitionKey;
import com.microsoft.azure.spring.data.cosmosdb.CosmosDbFactory;
import com.microsoft.azure.spring.data.cosmosdb.common.ResponseDiagnosticsTestUtils;
import com.microsoft.azure.spring.data.cosmosdb.common.TestConstants;
import com.microsoft.azure.spring.data.cosmosdb.config.CosmosDBConfig;
import com.microsoft.azure.spring.data.cosmosdb.core.convert.MappingCosmosConverter;
import com.microsoft.azure.spring.data.cosmosdb.core.mapping.CosmosMappingContext;
import com.microsoft.azure.spring.data.cosmosdb.core.query.Criteria;
import com.microsoft.azure.spring.data.cosmosdb.core.query.CriteriaType;
import com.microsoft.azure.spring.data.cosmosdb.core.query.DocumentQuery;
import com.microsoft.azure.spring.data.cosmosdb.domain.Person;
import com.microsoft.azure.spring.data.cosmosdb.exception.CosmosDBAccessException;
import com.microsoft.azure.spring.data.cosmosdb.repository.support.CosmosEntityInformation;
import io.reactivex.subscribers.TestSubscriber;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.domain.EntityScanner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.PropertySource;
import org.springframework.data.annotation.Persistent;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.microsoft.azure.spring.data.cosmosdb.common.TestConstants.DB_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

@RunWith(SpringJUnit4ClassRunner.class)
@PropertySource(value = { "classpath:application.properties" })
public class ReactiveCosmosTemplateIT {
    private static final Person TEST_PERSON = new Person(TestConstants.ID_1,
        TestConstants.FIRST_NAME,
        TestConstants.LAST_NAME, TestConstants.HOBBIES, TestConstants.ADDRESSES);

    private static final Person TEST_PERSON_2 = new Person(TestConstants.ID_2,
        TestConstants.NEW_FIRST_NAME,
        TestConstants.NEW_LAST_NAME, TestConstants.HOBBIES, TestConstants.ADDRESSES);

    private static final Person TEST_PERSON_3 = new Person(TestConstants.ID_3,
        TestConstants.NEW_FIRST_NAME,
        TestConstants.NEW_LAST_NAME, TestConstants.HOBBIES, TestConstants.ADDRESSES);

    private static final Person TEST_PERSON_4 = new Person(TestConstants.ID_4,
        TestConstants.NEW_FIRST_NAME,
        TestConstants.NEW_LAST_NAME, TestConstants.HOBBIES, TestConstants.ADDRESSES);

    @Value("${cosmosdb.uri}")
    private String cosmosDbUri;
    @Value("${cosmosdb.key}")
    private String cosmosDbKey;
    @Value("${cosmosdb.secondaryKey}")
    private String cosmosDbSecondaryKey;
    @Value("${cosmosdb.populateQueryMetrics}")
    private boolean populateQueryMetrics;

    private static ReactiveCosmosTemplate cosmosTemplate;
    private static String containerName;
    private static CosmosEntityInformation<Person, String> personInfo;
    private static CosmosKeyCredential cosmosKeyCredential;

    private static boolean initialized;
    private static ResponseDiagnosticsTestUtils responseDiagnosticsTestUtils;

    @Autowired
    private ApplicationContext applicationContext;

    @Before
    public void setUp() throws ClassNotFoundException {
        if (!initialized) {
            responseDiagnosticsTestUtils = new ResponseDiagnosticsTestUtils();
            cosmosKeyCredential = new CosmosKeyCredential(cosmosDbKey);
            final CosmosDBConfig dbConfig = CosmosDBConfig.builder(cosmosDbUri,
                cosmosKeyCredential, DB_NAME).build();
            dbConfig.setResponseDiagnosticsProcessor(responseDiagnosticsTestUtils.getResponseDiagnosticsProcessor());
            dbConfig.setPopulateQueryMetrics(populateQueryMetrics);
            final CosmosDbFactory dbFactory = new CosmosDbFactory(dbConfig);

            final CosmosMappingContext mappingContext = new CosmosMappingContext();
            personInfo = new CosmosEntityInformation<>(Person.class);
            containerName = personInfo.getCollectionName();

            mappingContext.setInitialEntitySet(new EntityScanner(this.applicationContext).scan(Persistent.class));

            final MappingCosmosConverter dbConverter =
                new MappingCosmosConverter(mappingContext, null);
            cosmosTemplate = new ReactiveCosmosTemplate(dbFactory, dbConverter, DB_NAME);
            cosmosTemplate.createCollectionIfNotExists(personInfo).block().container();
            initialized = true;
        }

        cosmosTemplate.insert(TEST_PERSON,
            new PartitionKey(personInfo.getPartitionKeyFieldValue(TEST_PERSON))).block();
    }

    @After
    public void cleanup() {
        //  Reset master key
        cosmosKeyCredential.key(cosmosDbKey);
        cosmosTemplate.deleteAll(Person.class.getSimpleName(),
            personInfo.getPartitionKeyFieldName()).block();
    }

    @Test
    public void testInsertDuplicateId() {
        final Mono<Person> insertMono = cosmosTemplate.insert(TEST_PERSON,
            new PartitionKey(personInfo.getPartitionKeyFieldValue(TEST_PERSON)));
        final TestSubscriber testSubscriber = new TestSubscriber();
        insertMono.subscribe(testSubscriber);
        testSubscriber.awaitTerminalEvent();
        testSubscriber.assertNotComplete();
        testSubscriber.assertTerminated();
        assertThat(testSubscriber.errors()).hasSize(1);
        assertThat(((List) testSubscriber.getEvents().get(1)).get(0))
            .isInstanceOf(CosmosDBAccessException.class);
    }

    @Test
    public void testFindByID() {
        final Mono<Person> findById = cosmosTemplate.findById(Person.class.getSimpleName(),
            TEST_PERSON.getId(),
            Person.class);
        StepVerifier.create(findById)
                    .consumeNextWith(actual -> Assert.assertEquals(actual, TEST_PERSON))
                    .verifyComplete();
        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNotNull();
    }

    @Test
    public void testFindByIDBySecondaryKey() {
        cosmosKeyCredential.key(cosmosDbSecondaryKey);
        final Mono<Person> findById = cosmosTemplate.findById(Person.class.getSimpleName(),
            TEST_PERSON.getId(),
            Person.class);
        StepVerifier.create(findById).consumeNextWith(actual -> {
            Assert.assertThat(actual.getFirstName(), is(equalTo(TEST_PERSON.getFirstName())));
            Assert.assertThat(actual.getLastName(), is(equalTo(TEST_PERSON.getLastName())));
        }).verifyComplete();

        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNotNull();
    }

    @Test
    public void testFindAll() {
        final Flux<Person> flux = cosmosTemplate.findAll(Person.class.getSimpleName(),
            Person.class);
        StepVerifier.create(flux).expectNextCount(1).verifyComplete();

        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNotNull();
    }

    @Test
    public void testFindByIdWithContainerName() {
        StepVerifier.create(cosmosTemplate.findById(Person.class.getSimpleName(),
            TEST_PERSON.getId(), Person.class))
                    .consumeNextWith(actual -> Assert.assertEquals(actual, TEST_PERSON))
                    .verifyComplete();

        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNotNull();
    }

    @Test
    public void testInsert() {
        StepVerifier.create(cosmosTemplate.insert(TEST_PERSON_3,
            new PartitionKey(personInfo.getPartitionKeyFieldValue(TEST_PERSON_3))))
                    .expectNext(TEST_PERSON_3).verifyComplete();

        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNull();
        assertThat(responseDiagnosticsTestUtils.getCosmosResponseDiagnostics()).isNotNull();
    }

    @Test
    public void testInsertBySecondaryKey() {
        cosmosKeyCredential.key(cosmosDbSecondaryKey);
        StepVerifier.create(cosmosTemplate.insert(TEST_PERSON_3,
            new PartitionKey(personInfo.getPartitionKeyFieldValue(TEST_PERSON_3))))
                    .expectNext(TEST_PERSON_3).verifyComplete();

        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNull();
        assertThat(responseDiagnosticsTestUtils.getCosmosResponseDiagnostics()).isNotNull();
    }

    @Test
    public void testInsertWithCollectionName() {
        StepVerifier.create(cosmosTemplate.insert(Person.class.getSimpleName(), TEST_PERSON_2,
            new PartitionKey(personInfo.getPartitionKeyFieldValue(TEST_PERSON_2))))
                    .expectNext(TEST_PERSON_2).verifyComplete();

        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNull();
        assertThat(responseDiagnosticsTestUtils.getCosmosResponseDiagnostics()).isNotNull();
    }

    @Test
    public void testUpsert() {
        final Person p = TEST_PERSON_2;
        final ArrayList<String> hobbies = new ArrayList<>(p.getHobbies());
        hobbies.add("more code");
        p.setHobbies(hobbies);
        final Mono<Person> upsert = cosmosTemplate.upsert(p,
            new PartitionKey(personInfo.getPartitionKeyFieldValue(p)));
        StepVerifier.create(upsert).expectNextCount(1).verifyComplete();

        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNull();
        assertThat(responseDiagnosticsTestUtils.getCosmosResponseDiagnostics()).isNotNull();
    }

    @Test
    public void testUpsertBySecondaryKey() {
        cosmosKeyCredential.key(cosmosDbSecondaryKey);
        final Person p = TEST_PERSON_2;
        final ArrayList<String> hobbies = new ArrayList<>(p.getHobbies());
        hobbies.add("more code");
        p.setHobbies(hobbies);
        final Mono<Person> upsert = cosmosTemplate.upsert(p,
            new PartitionKey(personInfo.getPartitionKeyFieldValue(p)));
        StepVerifier.create(upsert).expectNextCount(1).verifyComplete();

        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNull();
        assertThat(responseDiagnosticsTestUtils.getCosmosResponseDiagnostics()).isNotNull();
    }

    @Test
    public void testUpsertWithCollectionName() {
        final Person p = TEST_PERSON_2;
        final ArrayList<String> hobbies = new ArrayList<>(p.getHobbies());
        hobbies.add("more code");
        p.setHobbies(hobbies);
        final Mono<Person> upsert = cosmosTemplate.upsert(Person.class.getSimpleName(), p,
            new PartitionKey(personInfo.getPartitionKeyFieldValue(p)));
        StepVerifier.create(upsert).expectNextCount(1).verifyComplete();

        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNull();
        assertThat(responseDiagnosticsTestUtils.getCosmosResponseDiagnostics()).isNotNull();
    }

    @Test
    public void testDeleteById() {
        cosmosTemplate.insert(TEST_PERSON_4,
            new PartitionKey(personInfo.getPartitionKeyFieldValue(TEST_PERSON_4))).block();

        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNull();
        assertThat(responseDiagnosticsTestUtils.getCosmosResponseDiagnostics()).isNotNull();

        Flux<Person> flux = cosmosTemplate.findAll(Person.class.getSimpleName(), Person.class);
        StepVerifier.create(flux).expectNextCount(2).verifyComplete();

        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNotNull();
        assertThat(responseDiagnosticsTestUtils.getCosmosResponseDiagnostics()).isNull();

        final Mono<Void> voidMono = cosmosTemplate.deleteById(Person.class.getSimpleName(),
            TEST_PERSON_4.getId(),
            new PartitionKey(personInfo.getPartitionKeyFieldValue(TEST_PERSON_4)));
        StepVerifier.create(voidMono).verifyComplete();

        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNull();
        assertThat(responseDiagnosticsTestUtils.getCosmosResponseDiagnostics()).isNotNull();

        flux = cosmosTemplate.findAll(Person.class.getSimpleName(), Person.class);
        StepVerifier.create(flux).expectNextCount(1).verifyComplete();

        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNotNull();
        assertThat(responseDiagnosticsTestUtils.getCosmosResponseDiagnostics()).isNull();
    }

    @Test
    public void testDeleteByIdBySecondaryKey() {
        cosmosKeyCredential.key(cosmosDbSecondaryKey);
        cosmosTemplate.insert(TEST_PERSON_4,
            new PartitionKey(personInfo.getPartitionKeyFieldValue(TEST_PERSON_4))).block();
        Flux<Person> flux = cosmosTemplate.findAll(Person.class.getSimpleName(), Person.class);
        StepVerifier.create(flux).expectNextCount(2).verifyComplete();
        final Mono<Void> voidMono = cosmosTemplate.deleteById(Person.class.getSimpleName(),
            TEST_PERSON_4.getId(),
            new PartitionKey(personInfo.getPartitionKeyFieldValue(TEST_PERSON_4)));
        StepVerifier.create(voidMono).verifyComplete();
        flux = cosmosTemplate.findAll(Person.class.getSimpleName(), Person.class);
        StepVerifier.create(flux).expectNextCount(1).verifyComplete();
    }

    @Test
    public void testFind() {
        final Criteria criteria = Criteria.getInstance(CriteriaType.IS_EQUAL, "firstName",
            Arrays.asList(TEST_PERSON.getFirstName()));
        final DocumentQuery query = new DocumentQuery(criteria);
        final Flux<Person> personFlux = cosmosTemplate.find(query, Person.class,
            Person.class.getSimpleName());
        StepVerifier.create(personFlux).expectNextCount(1).verifyComplete();

        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNotNull();
        assertThat(responseDiagnosticsTestUtils.getCosmosResponseDiagnostics()).isNull();
    }

    @Test
    public void testExists() {
        final Criteria criteria = Criteria.getInstance(CriteriaType.IS_EQUAL, "firstName",
            Arrays.asList(TEST_PERSON.getFirstName()));
        final DocumentQuery query = new DocumentQuery(criteria);
        final Mono<Boolean> exists = cosmosTemplate.exists(query, Person.class, containerName);
        StepVerifier.create(exists).expectNext(true).verifyComplete();

        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNotNull();
        assertThat(responseDiagnosticsTestUtils.getCosmosResponseDiagnostics()).isNull();
    }

    @Test
    public void testCount() {
        final Mono<Long> count = cosmosTemplate.count(containerName);
        StepVerifier.create(count).expectNext((long) 1).verifyComplete();

        assertThat(responseDiagnosticsTestUtils.getFeedResponseDiagnostics()).isNotNull();
        assertThat(responseDiagnosticsTestUtils.getCosmosResponseDiagnostics()).isNull();
    }

    @Test
    public void testCountBySecondaryKey() {
        cosmosKeyCredential.key(cosmosDbSecondaryKey);
        final Mono<Long> count = cosmosTemplate.count(containerName);
        StepVerifier.create(count).expectNext((long) 1).verifyComplete();
    }

    @Test
    public void testInvalidSecondaryKey() {
        cosmosKeyCredential.key("Invalid secondary key");
        final Mono<Person> findById = cosmosTemplate.findById(Person.class.getSimpleName(),
            TEST_PERSON.getId(),
            Person.class);
        StepVerifier.create(findById).expectError(IllegalArgumentException.class);
    }

}
