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
package org.apache.polaris.service.quarkus.catalog;

import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import io.quarkus.test.junit.QuarkusMock;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.SecurityContext;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.view.View;
import org.apache.iceberg.view.ViewCatalogTests;
import org.apache.polaris.core.PolarisCallContext;
import org.apache.polaris.core.PolarisDiagnostics;
import org.apache.polaris.core.admin.model.CreateCatalogRequest;
import org.apache.polaris.core.admin.model.FileStorageConfigInfo;
import org.apache.polaris.core.admin.model.StorageConfigInfo;
import org.apache.polaris.core.auth.AuthenticatedPolarisPrincipal;
import org.apache.polaris.core.auth.PolarisAuthorizerImpl;
import org.apache.polaris.core.config.FeatureConfiguration;
import org.apache.polaris.core.config.PolarisConfigurationStore;
import org.apache.polaris.core.context.RealmContext;
import org.apache.polaris.core.entity.CatalogEntity;
import org.apache.polaris.core.entity.PrincipalEntity;
import org.apache.polaris.core.persistence.MetaStoreManagerFactory;
import org.apache.polaris.core.persistence.PolarisEntityManager;
import org.apache.polaris.core.persistence.PolarisMetaStoreManager;
import org.apache.polaris.core.persistence.resolver.ResolverFactory;
import org.apache.polaris.core.secrets.UserSecretsManager;
import org.apache.polaris.core.secrets.UserSecretsManagerFactory;
import org.apache.polaris.core.storage.cache.StorageCredentialCache;
import org.apache.polaris.service.admin.PolarisAdminService;
import org.apache.polaris.service.catalog.PolarisPassthroughResolutionView;
import org.apache.polaris.service.catalog.iceberg.IcebergCatalog;
import org.apache.polaris.service.catalog.io.DefaultFileIOFactory;
import org.apache.polaris.service.catalog.io.FileIOFactory;
import org.apache.polaris.service.config.ReservedProperties;
import org.apache.polaris.service.events.AfterViewCommitedEvent;
import org.apache.polaris.service.events.AfterViewRefreshedEvent;
import org.apache.polaris.service.events.BeforeViewCommitedEvent;
import org.apache.polaris.service.events.BeforeViewRefreshedEvent;
import org.apache.polaris.service.events.PolarisEventListener;
import org.apache.polaris.service.events.TestPolarisEventListener;
import org.apache.polaris.service.quarkus.test.TestData;
import org.apache.polaris.service.storage.PolarisStorageIntegrationProviderImpl;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.Assumptions;
import org.assertj.core.configuration.PreferredAssumptionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

public abstract class AbstractIcebergCatalogViewTest extends ViewCatalogTests<IcebergCatalog> {
  static {
    Assumptions.setPreferredAssumptionException(PreferredAssumptionException.JUNIT5);
  }

  public static class Profile extends Profiles.DefaultProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return ImmutableMap.<String, String>builder()
          .putAll(super.getConfigOverrides())
          .put("polaris.features.\"ALLOW_WILDCARD_LOCATION\"", "true")
          .put("polaris.features.\"SKIP_CREDENTIAL_SUBSCOPING_INDIRECTION\"", "true")
          .put("polaris.features.\"LIST_PAGINATION_ENABLED\"", "true")
          .build();
    }
  }

  public static final String CATALOG_NAME = "polaris-catalog";

  public static Map<String, String> VIEW_PREFIXES =
      Map.of(
          CatalogProperties.VIEW_DEFAULT_PREFIX + "key1", "catalog-default-key1",
          CatalogProperties.VIEW_DEFAULT_PREFIX + "key2", "catalog-default-key2",
          CatalogProperties.VIEW_DEFAULT_PREFIX + "key3", "catalog-default-key3",
          CatalogProperties.VIEW_OVERRIDE_PREFIX + "key3", "catalog-override-key3",
          CatalogProperties.VIEW_OVERRIDE_PREFIX + "key4", "catalog-override-key4");

  @Inject MetaStoreManagerFactory metaStoreManagerFactory;
  @Inject UserSecretsManagerFactory userSecretsManagerFactory;
  @Inject PolarisConfigurationStore configurationStore;
  @Inject StorageCredentialCache storageCredentialCache;
  @Inject PolarisDiagnostics diagServices;
  @Inject PolarisEventListener polarisEventListener;
  @Inject ResolverFactory resolverFactory;

  private IcebergCatalog catalog;

  private String realmName;
  private PolarisMetaStoreManager metaStoreManager;
  private UserSecretsManager userSecretsManager;
  private PolarisCallContext polarisContext;

  private TestPolarisEventListener testPolarisEventListener;

  @BeforeAll
  public static void setUpMocks() {
    PolarisStorageIntegrationProviderImpl mock =
        Mockito.mock(PolarisStorageIntegrationProviderImpl.class);
    QuarkusMock.installMockForType(mock, PolarisStorageIntegrationProviderImpl.class);
  }

  @BeforeEach
  public void setUpTempDir(@TempDir Path tempDir) throws Exception {
    // see https://github.com/quarkusio/quarkus/issues/13261
    Field field = ViewCatalogTests.class.getDeclaredField("tempDir");
    field.setAccessible(true);
    field.set(this, tempDir);
  }

  protected void bootstrapRealm(String realmName) {}

  @BeforeEach
  public void before(TestInfo testInfo) {
    storageCredentialCache.invalidateAll();

    realmName =
        "realm_%s_%s"
            .formatted(
                testInfo.getTestMethod().map(Method::getName).orElse("test"), System.nanoTime());
    bootstrapRealm(realmName);
    RealmContext realmContext = () -> realmName;
    QuarkusMock.installMockForType(realmContext, RealmContext.class);

    metaStoreManager = metaStoreManagerFactory.getOrCreateMetaStoreManager(realmContext);
    userSecretsManager = userSecretsManagerFactory.getOrCreateUserSecretsManager(realmContext);
    polarisContext =
        new PolarisCallContext(
            realmContext,
            metaStoreManagerFactory.getOrCreateSession(realmContext),
            diagServices,
            configurationStore,
            Clock.systemDefaultZone());

    PolarisEntityManager entityManager =
        new PolarisEntityManager(metaStoreManager, resolverFactory);

    PrincipalEntity rootPrincipal =
        metaStoreManager.findRootPrincipal(polarisContext).orElseThrow();
    AuthenticatedPolarisPrincipal authenticatedRoot =
        new AuthenticatedPolarisPrincipal(rootPrincipal, Set.of());

    SecurityContext securityContext = Mockito.mock(SecurityContext.class);
    when(securityContext.getUserPrincipal()).thenReturn(authenticatedRoot);
    when(securityContext.isUserInRole(Mockito.anyString())).thenReturn(true);

    ReservedProperties reservedProperties = ReservedProperties.NONE;

    PolarisAdminService adminService =
        new PolarisAdminService(
            polarisContext,
            entityManager,
            metaStoreManager,
            userSecretsManager,
            securityContext,
            new PolarisAuthorizerImpl(),
            reservedProperties);
    adminService.createCatalog(
        new CreateCatalogRequest(
            new CatalogEntity.Builder()
                .setName(CATALOG_NAME)
                .addProperty(
                    FeatureConfiguration.ALLOW_EXTERNAL_TABLE_LOCATION.catalogConfig(), "true")
                .addProperty(
                    FeatureConfiguration.ALLOW_UNSTRUCTURED_TABLE_LOCATION.catalogConfig(), "true")
                .addProperty(FeatureConfiguration.DROP_WITH_PURGE_ENABLED.catalogConfig(), "true")
                .setDefaultBaseLocation("file://tmp")
                .setStorageConfigurationInfo(
                    polarisContext,
                    new FileStorageConfigInfo(
                        StorageConfigInfo.StorageTypeEnum.FILE, List.of("file://", "/", "*")),
                    "file://tmp")
                .build()
                .asCatalog()));

    PolarisPassthroughResolutionView passthroughView =
        new PolarisPassthroughResolutionView(
            polarisContext, entityManager, securityContext, CATALOG_NAME);
    FileIOFactory fileIOFactory =
        new DefaultFileIOFactory(storageCredentialCache, metaStoreManagerFactory);

    testPolarisEventListener = (TestPolarisEventListener) polarisEventListener;
    this.catalog =
        new IcebergCatalog(
            storageCredentialCache,
            resolverFactory,
            metaStoreManager,
            polarisContext,
            passthroughView,
            securityContext,
            Mockito.mock(),
            fileIOFactory,
            polarisEventListener);
    Map<String, String> properties =
        ImmutableMap.<String, String>builder()
            .put(CatalogProperties.FILE_IO_IMPL, "org.apache.iceberg.inmemory.InMemoryFileIO")
            .putAll(VIEW_PREFIXES)
            .build();
    this.catalog.initialize(CATALOG_NAME, properties);
  }

  @AfterEach
  public void after() throws IOException {
    catalog().close();
    metaStoreManager.purge(polarisContext);
  }

  @Override
  protected IcebergCatalog catalog() {
    return catalog;
  }

  @Override
  protected Catalog tableCatalog() {
    return catalog;
  }

  @Override
  protected boolean requiresNamespaceCreate() {
    return true;
  }

  @Test
  public void testEventsAreEmitted() {
    IcebergCatalog catalog = catalog();
    catalog.createNamespace(TestData.NAMESPACE);
    View view =
        catalog
            .buildView(TestData.TABLE)
            .withDefaultNamespace(TestData.NAMESPACE)
            .withSchema(TestData.SCHEMA)
            .withQuery("a", "b")
            .create();

    String key = "foo";
    String valOld = "bar1";
    String valNew = "bar2";
    view.updateProperties().set(key, valOld).commit();
    view.updateProperties().set(key, valNew).commit();

    var beforeRefreshEvent = testPolarisEventListener.getLatest(BeforeViewRefreshedEvent.class);
    Assertions.assertThat(beforeRefreshEvent.viewIdentifier()).isEqualTo(TestData.TABLE);

    var afterRefreshEvent = testPolarisEventListener.getLatest(AfterViewRefreshedEvent.class);
    Assertions.assertThat(afterRefreshEvent.viewIdentifier()).isEqualTo(TestData.TABLE);

    var beforeCommitEvent = testPolarisEventListener.getLatest(BeforeViewCommitedEvent.class);
    Assertions.assertThat(beforeCommitEvent.identifier()).isEqualTo(TestData.TABLE);
    Assertions.assertThat(beforeCommitEvent.base().properties().get(key)).isEqualTo(valOld);
    Assertions.assertThat(beforeCommitEvent.metadata().properties().get(key)).isEqualTo(valNew);

    var afterCommitEvent = testPolarisEventListener.getLatest(AfterViewCommitedEvent.class);
    Assertions.assertThat(afterCommitEvent.identifier()).isEqualTo(TestData.TABLE);
    Assertions.assertThat(afterCommitEvent.base().properties().get(key)).isEqualTo(valOld);
    Assertions.assertThat(afterCommitEvent.metadata().properties().get(key)).isEqualTo(valNew);
  }
}
