/*
 * Copyright (C) 2014 Redpill Linpro AB
 *
 * This file is part of Findwise Integration module for Alfresco
 *
 * Findwise Integration module for Alfresco is free software: 
 * you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Findwise Integration module for Alfresco is distributed in the 
 * hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Findwise Integration module for Alfresco. 
 * If not, see <http://www.gnu.org/licenses/>.
 */

package org.redpill.alfresco.repo.findwise;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import org.alfresco.model.ContentModel;
import org.alfresco.repo.policy.BehaviourFilter;
import org.alfresco.service.cmr.dictionary.DictionaryService;
import org.alfresco.service.cmr.dictionary.PropertyDefinition;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentService;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Logger;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.redpill.alfresco.repo.findwise.bean.FindwiseFieldBean;
import org.redpill.alfresco.repo.findwise.bean.FindwiseObjectBean;
import org.redpill.alfresco.repo.findwise.model.FindwiseIntegrationModel;
import org.redpill.alfresco.repo.findwise.processor.NodeVerifierProcessor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.util.Assert;

import com.google.gson.Gson;

public class SearchIntegrationServiceImpl implements SearchIntegrationService, InitializingBean {
  private static final Logger LOG = Logger.getLogger(SearchIntegrationServiceImpl.class);
  protected NodeService nodeService;
  protected DictionaryService dictionaryService;
  protected NamespaceService namespaceService;
  protected ContentService contentService;
  protected BehaviourFilter behaviourFilter;
  protected Boolean pushEnabled;
  protected String pushUrl;
  protected NodeVerifierProcessor nodeVerifierProcessor;

  @Override
  public void afterPropertiesSet() throws Exception {
    Assert.notNull(nodeService);
    Assert.notNull(namespaceService);
    Assert.notNull(dictionaryService);
    Assert.notNull(contentService);
    Assert.notNull(pushEnabled);
    Assert.notNull(pushUrl);
    Assert.notNull(nodeVerifierProcessor);
    Assert.notNull(behaviourFilter);
  }

  public void setBehaviourFilter(BehaviourFilter behaviourFilter) {
    this.behaviourFilter = behaviourFilter;
  }

  @Override
  public void setNodeVerifierProcessor(NodeVerifierProcessor nodeVerifierProcessor) {
    this.nodeVerifierProcessor = nodeVerifierProcessor;
  }

  public void setNodeService(NodeService nodeService) {
    this.nodeService = nodeService;
  }

  public void setDictionaryService(DictionaryService dictionaryService) {
    this.dictionaryService = dictionaryService;
  }

  public void setNamespaceService(NamespaceService namespaceService) {
    this.namespaceService = namespaceService;
  }

  public void setContentService(ContentService contentService) {
    this.contentService = contentService;
  }

  public void setPushEnabled(Boolean pushEnabled) {
    this.pushEnabled = pushEnabled;
  }

  public void setPushUrl(String pushUrl) {
    this.pushUrl = pushUrl;
  }

  @Override
  public void pushUpdateToIndexService(final NodeRef nodeRef, final String action) {
    if (LOG.isTraceEnabled()) {
      LOG.trace("pushUpdateToIndexService begin");
    }

    boolean send = false;
    List<FindwiseObjectBean> fobs = new ArrayList<FindwiseObjectBean>();

    if (ACTION_CREATE.equals(action)) {
      if (nodeRef == null || !nodeService.exists(nodeRef)) {
        LOG.debug(nodeRef + " does not exist");
      } else if (!nodeVerifierProcessor.verifyDocument(nodeRef)) {
        LOG.debug(nodeRef + " did not pass final node verification");
      } else {
        FindwiseObjectBean fob = createFindwiseObjectBean(nodeRef, false);
        fobs.add(fob);
        send = true;
      }
    } else if (ACTION_DELETE.equals(action)) {
      FindwiseObjectBean fob = createFindwiseObjectBean(nodeRef, true);
      fobs.add(fob);
      send = true;
    } else {
      throw new UnsupportedOperationException(action + " is not a supported operation");
    }

    if (send) {
      Gson gson = new Gson();
      String json = gson.toJson(fobs);
      if (LOG.isTraceEnabled()) {
        if (json.length() < 102400) {
          LOG.trace("Json: " + json);
        } else {
          LOG.trace("Omitting json trace printout due to its size");
        }
      }
      if (Boolean.TRUE.equals(pushEnabled)) {
        final boolean pushResult = doPost(json);
        if (nodeService.exists(nodeRef)) {
          LOG.debug("Setting push result on node " + nodeRef);

          behaviourFilter.disableBehaviour(nodeRef);

          nodeService.setProperty(nodeRef, FindwiseIntegrationModel.PROP_LAST_PUSH_TO_INDEX, new Date());
          if (pushResult == true) {
            // Success
            nodeService.setProperty(nodeRef, FindwiseIntegrationModel.PROP_LAST_PUSH_FAILED, false);
          } else {
            // Failed
            nodeService.setProperty(nodeRef, FindwiseIntegrationModel.PROP_LAST_PUSH_FAILED, true);
          }
          behaviourFilter.enableBehaviour(nodeRef);
        }
      } else {
        LOG.info("Push is disabled");
      }
    }
    if (LOG.isTraceEnabled()) {
      LOG.trace("pushUpdateToIndexService end");
    }
  }
  
  protected boolean isPropertyAllowedToIndex(QName property) {
    if (ContentModel.PROP_AUTO_VERSION.equals(property)) {
      return false;
    } else if (ContentModel.PROP_AUTO_VERSION_PROPS.equals(property)) {
      return false;
    } else if (ContentModel.PROP_INITIAL_VERSION.equals(property)) {
      return false;
    } else {
      return true;
    }
  }

  /**
   * Create bean object from node properties
   * 
   * @param nodeRef
   * @return
   */
  protected FindwiseObjectBean createFindwiseObjectBean(final NodeRef nodeRef, boolean empty) {
    FindwiseObjectBean fob = new FindwiseObjectBean();
    fob.setId(nodeRef.toString());
    if (empty == false) {
      List<FindwiseFieldBean> fields = new ArrayList<FindwiseFieldBean>();

      Map<QName, Serializable> properties = nodeService.getProperties(nodeRef);
      // TODO add node type property
      Iterator<QName> it = properties.keySet().iterator();
      while (it.hasNext()) {
        FindwiseFieldBean ffb = new FindwiseFieldBean();
        QName property = it.next();
        Serializable value = properties.get(property);
        if (NamespaceService.SYSTEM_MODEL_1_0_URI.equals(property.getNamespaceURI()) ||
            FindwiseIntegrationModel.URI.equals(property.getNamespaceURI()) ||
            !isPropertyAllowedToIndex(property)) {
          if (LOG.isTraceEnabled()) {
            LOG.trace("Skiping property " + property.toString());
          }
          continue;
        }
        PropertyDefinition propertyDefinition = dictionaryService.getProperty(property);
        String javaClassName = propertyDefinition.getDataType().getJavaClassName();
        String type;
        if (LOG.isTraceEnabled()) {
          LOG.trace("Detected " + javaClassName + " java type for property " + property.toString());
        }
        /*
         * if ("java.lang.String".equals(javaClassName)) { type = "string";
         * ffb.setValue(value); } else if
         * ("java.lang.Integer".equals(javaClassName)) { type = "integer";
         * ffb.setValue(value); } else if
         * ("java.lang.Long".equals(javaClassName)) { type = "long";
         * ffb.setValue(value); } else if
         * ("java.lang.Boolean".equals(javaClassName)) { type = "boolean";
         * ffb.setValue(value); } else if
         * ("java.lang.Date".equals(javaClassName)) { type = "date";
         * ffb.setValue(value); } else
         */
        if ("java.util.Date".equals(javaClassName)) {
          if (LOG.isTraceEnabled()) {
            LOG.trace("Converting " + property.toString() + " to date");
          }
          type = "string";
          //SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-DDThh:mm:ssZ");
          //sdf.setTimeZone(TimeZone.getTimeZone("UTC"));          
          DateTime date = new DateTime( (Date) value, DateTimeZone.UTC );
          ffb.setValue(date.toString());
        } else if ("org.alfresco.service.cmr.repository.ContentData".equals(javaClassName)) {
          // Create Base64 data
          if (LOG.isTraceEnabled()) {
            LOG.trace("Skipping field " + property.toString());
          }
          ContentReader contentReader = contentService.getReader(nodeRef, property);
          InputStream nodeIS = new BufferedInputStream(contentReader.getContentInputStream(), 4096);

          try {
            byte[] nodeBytes = IOUtils.toByteArray(nodeIS);
            ffb.setValue(new String(Base64.encodeBase64(nodeBytes)));
          } catch (IOException e) {
            LOG.warn("Error while reading content", e);
          }
          type = "binary";
        } else {
          if (LOG.isDebugEnabled()) {
            LOG.debug("Unhandled property type, using default conversion");
          }
          type = "string";
          ffb.setValue(value.toString());
        }
        ffb.setType(type);

        String name = property.toPrefixString(namespaceService);
        if (LOG.isTraceEnabled()) {
          LOG.trace("Short name for property " + property.toString() + ": " + name);
        }

        ffb.setName(name);

        fields.add(ffb);
      }
      fob.setFields(fields);
    }
    return fob;
  }

  protected boolean doPost(final String json) {
    boolean result = false;
    DefaultHttpClient httpclient = new DefaultHttpClient();
    try {
      HttpPost httpPost = new HttpPost(pushUrl);
      StringEntity entity = new StringEntity(json, "UTF-8");
      httpPost.setEntity(entity);
      if (LOG.isDebugEnabled()) {
        LOG.debug("Executing request: " + httpPost.getRequestLine());
      }
      httpPost.addHeader("Content-Type", "application/json;charset=UTF-8");
      HttpResponse response = httpclient.execute(httpPost);
      try {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Response" + response.getStatusLine());
        }
        EntityUtils.consume(response.getEntity());
        result = true;
      } finally {
        // response.close();
      }
    } catch (UnsupportedEncodingException e) {
      LOG.warn("Error transforming json to http entity. Json: " + json, e);
    } catch (Exception e) {
      LOG.warn("Error executing http post to " + pushUrl + " Json: " + json, e);
    } finally {
      /*
       * try { //httpclient.close(); } catch (IOException e) {
       * LOG.warn("Error making post to " + pushUrl, e); }
       */
    }
    return result;
  }

  // TODO delete
  protected boolean doDelete(final String json) {
    boolean result = false;
    /*
     * DefaultHttpClient httpclient = new DefaultHttpClient(); try { HttpDelete
     * httpDelete = new HttpDelete(pushUrl); StringEntity entity = new
     * StringEntity(json, "UTF-8"); httpDelete.setEntity(entity); httpDelete.set
     * if (LOG.isDebugEnabled()) { LOG.debug("Executing request: " +
     * httpDelete.getRequestLine()); } httpDelete.addHeader("Content-Type",
     * "application/json;charset=UTF-8"); HttpResponse response =
     * httpclient.execute(httpDelete); try { if (LOG.isDebugEnabled()) {
     * LOG.debug("Response" + response.getStatusLine()); }
     * EntityUtils.consume(response.getEntity()); result = true; } finally { //
     * response.close(); } } catch (UnsupportedEncodingException e) {
     * LOG.warn("Error transforming json to http entity. Json: " + json, e); }
     * catch (Exception e) { LOG.warn("Error executing http post to " + pushUrl
     * + " Json: " + json, e); } finally { /* try { //httpclient.close(); }
     * catch (IOException e) { LOG.warn("Error making post to " + pushUrl, e); }
     * 
     * }
     */
    return result;
  }

}
