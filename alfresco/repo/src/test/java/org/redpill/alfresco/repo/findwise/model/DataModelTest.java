package org.redpill.alfresco.repo.findwise.model;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.alfresco.repo.dictionary.CompiledModelsCache;
import org.alfresco.repo.dictionary.DictionaryBootstrap;
import org.alfresco.repo.dictionary.DictionaryDAOImpl;
import org.alfresco.repo.tenant.SingleTServiceImpl;
import org.alfresco.repo.tenant.TenantService;
import org.alfresco.util.DynamicallySizedThreadPoolExecutor;
import org.alfresco.util.TraceableThreadFactory;
import org.alfresco.util.cache.DefaultAsynchronouslyRefreshedCacheRegistry;
import org.junit.Test;

public class DataModelTest {

  @Test
  public void svkModelTest() {
    Integer result = TestModel.testModel(new String[] { "alfresco/module/findwise/context/model/findwiseIntegrationModel.xml" });
    assertEquals("Invalid model", new Integer(0), result);
  }

  /*
   * Copyright (C) 2005-2010 Alfresco Software Limited.
   * 
   * This file is part of Alfresco
   * 
   * Alfresco is free software: you can redistribute it and/or modify it under
   * the terms of the GNU Lesser General Public License as published by the Free
   * Software Foundation, either version 3 of the License, or (at your option)
   * any later version.
   * 
   * Alfresco is distributed in the hope that it will be useful, but WITHOUT ANY
   * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
   * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for
   * more details.
   * 
   * You should have received a copy of the GNU Lesser General Public License
   * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
   */
  /**
   * Test Model Definitions
   */
  public static class TestModel {
    /**
     * Test model
     * 
     * Java command line client <br />
     * Syntax: <br />
     * TestModel [-h] [model filename]*
     * <p>
     * Returns 0 for success.
     */
    public static int testModel(String[] args) {
      if (args != null && args.length > 0 && args[0].equals("-h")) {
        System.out.println("TestModel [model filename]*");
        return 1;
      }

      System.out.println("Testing dictionary model definitions...");

      // construct list of models to test
      // include alfresco defaults
      List<String> bootstrapModels = new ArrayList<String>();
      bootstrapModels.add("alfresco/model/dictionaryModel.xml");
      bootstrapModels.add("alfresco/model/systemModel.xml");
      bootstrapModels.add("org/alfresco/repo/security/authentication/userModel.xml");
      bootstrapModels.add("alfresco/model/contentModel.xml");
      bootstrapModels.add("alfresco/model/applicationModel.xml");
      bootstrapModels.add("alfresco/model/bpmModel.xml");
      bootstrapModels.add("alfresco/model/datalistModel.xml");
      bootstrapModels.add("alfresco/workflow/workflowModel.xml");
      bootstrapModels.add("alfresco/model/siteModel.xml");
      // include models specified on command line
      for (String arg : args) {
        bootstrapModels.add(arg);
      }

      for (String model : bootstrapModels) {
        System.out.println(" " + model);
      }

      // construct dictionary dao
      TenantService tenantService = new SingleTServiceImpl();

      // NamespaceDAO namespaceDAO = new NamespaceDAOImpl();
      // namespaceDAO.setTenantService(tenantService);
      // initNamespaceCaches(namespaceDAO);

      DictionaryDAOImpl dictionaryDAO = new DictionaryDAOImpl();
      dictionaryDAO.setTenantService(tenantService);

      initDictionaryCaches(dictionaryDAO, tenantService);

      // bootstrap dao
      try {
        DictionaryBootstrap bootstrap = new DictionaryBootstrap();
        bootstrap.setModels(bootstrapModels);
        bootstrap.setDictionaryDAO(dictionaryDAO);
        bootstrap.bootstrap();
        System.out.println("Models are valid.");

        return 0; // Success

      } catch (Exception e) {
        System.out.println("Found an invalid model...");
        e.printStackTrace();
        Throwable t = e;
        while (t != null) {
          System.out.println(t.getMessage());
          t = t.getCause();
        }
        return 2; // Not Success
      }
    }

    private static void initDictionaryCaches(DictionaryDAOImpl dictionaryDAO, TenantService tenantService) {
      CompiledModelsCache compiledModelsCache = new CompiledModelsCache();
      compiledModelsCache.setDictionaryDAO(dictionaryDAO);
      compiledModelsCache.setTenantService(tenantService);
      compiledModelsCache.setRegistry(new DefaultAsynchronouslyRefreshedCacheRegistry());
      TraceableThreadFactory threadFactory = new TraceableThreadFactory();
      threadFactory.setThreadDaemon(true);
      threadFactory.setThreadPriority(Thread.NORM_PRIORITY);

      ThreadPoolExecutor threadPoolExecutor = new DynamicallySizedThreadPoolExecutor(20, 20, 90, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), threadFactory,
          new ThreadPoolExecutor.CallerRunsPolicy());
      compiledModelsCache.setThreadPoolExecutor(threadPoolExecutor);
      dictionaryDAO.setDictionaryRegistryCache(compiledModelsCache);
      dictionaryDAO.init();
    }

  }
}
