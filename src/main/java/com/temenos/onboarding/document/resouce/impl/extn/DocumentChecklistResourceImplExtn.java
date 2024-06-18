package com.temenos.onboarding.document.resouce.impl.extn;

import com.dbp.core.api.factory.impl.DBPAPIAbstractFactoryImpl;
import com.dbp.core.error.DBPApplicationException;
import com.infinity.dbx.jwt.auth.AuthenticationOnboarding;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.Predicate;
import com.jayway.jsonpath.ReadContext;
import com.konylabs.middleware.controller.DataControllerRequest;
import com.konylabs.middleware.controller.DataControllerResponse;
import com.konylabs.middleware.dataobject.Dataset;
import com.konylabs.middleware.dataobject.JSONToResult;
import com.konylabs.middleware.dataobject.Result;
import com.temenos.onboarding.commons.errorhandling.ErrorCodeEnum;
import com.temenos.onboarding.commons.errorhandling.OnBoardingException;
import com.temenos.onboarding.commons.utils.CommonUtils;
import com.temenos.onboarding.document.businessdelegate.api.DocumentBusinessDelegate;
import com.temenos.onboarding.document.businessdelegate.api.RulesEngineBusinessDelegate;
import com.temenos.onboarding.document.businessdelegate.api.StorageBusinessDelegate;
import com.temenos.onboarding.document.config.ServerConfigurations;
import com.temenos.onboarding.document.resource.impl.DocumentChecklistResourceImpl;
import com.temenos.onboarding.utilities.IdentityUtilities;
import com.temenos.onboarding.services.OnboardingServices;
import com.temenos.onboarding.utilities.OnboardingUtilities;
import com.temenos.onboarding.utilities.SessionManager;
import com.temenos.storage.backenddelegate.api.StorageBackendDelegate;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

public class DocumentChecklistResourceImplExtn extends DocumentChecklistResourceImpl {
    private static final Logger logger = LogManager.getLogger(DocumentChecklistResourceImpl.class);
    private static final List<String> entityDocList = new ArrayList(Arrays.asList("ProofOfBusiness", "ProofOfID", "ProofOfAddress", "FinancialStatement", "ProofOfIncome", "SupportingFinancialDocuments", "Signature", "HostDocuments"));

    public Result getRequiredDocuments(String methodId, Object[] inputArray, DataControllerRequest request, DataControllerResponse response) throws DBPApplicationException, Exception {
        Result result = new Result();
        JSONArray inputs = new JSONArray();
        String entityDefintionCode = SessionManager.FORM_CODE.retreiveFromSession(request);
        String applicationId = SessionManager.APPLICATION_ID.retreiveFromSession(request);
        String existingMemberCoAppKey = "";
        String existingMemberDigitalProfileID = "";
        if (request.containsKeyInRequest("ExistingMemberCoAppKey")) {
            existingMemberCoAppKey = request.getParameter("ExistingMemberCoAppKey");
            String digitalProfileIds = SessionManager.DIGITAL_PROFILE_IDS.retreiveFromSession(request);
            if (StringUtils.isNotEmpty(digitalProfileIds)) {
                JSONObject coApplicantMapping = new JSONObject(digitalProfileIds);
                if (coApplicantMapping.has(existingMemberCoAppKey)) {
                    existingMemberDigitalProfileID = coApplicantMapping.getString(existingMemberCoAppKey);
                }
            }
        }

        JSONObject documentChecklistConfig = this.getDocumentChecklistConfig(request);
        JSONArray applicationDetails = this.fetchODMSDetails(entityDefintionCode, applicationId);
        JSONObject metadatObj = new JSONObject(this.getEntityItemEntry("MetaData", applicationDetails, "MetaData").getString("entry"));
        int noOfCoapplicants = entityDefintionCode.equalsIgnoreCase("SMEOnboarding") ? metadatObj.getJSONArray("CoApplicants").length() + 1 : metadatObj.getJSONArray("CoApplicants").length();
        JSONArray resultObj = new JSONArray();

        for(int i = -1; i < noOfCoapplicants; ++i) {
            String digitalProfileId;
            if (entityDefintionCode.equalsIgnoreCase("SMEOnboarding")) {
                if (i == -1) {
                    digitalProfileId = applicationId + "_Company";
                } else {
                    digitalProfileId = i == 0 ? metadatObj.getString("PrimaryApplicant").split("_")[1] : metadatObj.getJSONArray("CoApplicants").getString(i - 1).split("_")[1];
                }
            } else {
                digitalProfileId = i == -1 ? metadatObj.getString("PrimaryApplicant").split("_")[1] : metadatObj.getJSONArray("CoApplicants").getString(i).split("_")[1];
            }

            if ((!entityDefintionCode.equalsIgnoreCase("SMEOnboarding") || noOfCoapplicants != 1) && i > -1 && existingMemberCoAppKey.isEmpty()) {
                String coApplicantSectionName = "ApplicantMetaData_" + digitalProfileId;
                JSONObject coApplicantEntry = new JSONObject(this.getEntityItemEntry("ApplicantMetaData", applicationDetails, coApplicantSectionName).getString("entry"));
                if (coApplicantEntry.has("IsExistingCustomer") && (coApplicantEntry.optString("IsExistingCustomer").equalsIgnoreCase("Yes") || coApplicantEntry.optString("IsExistingCustomer").equalsIgnoreCase("true"))) {
                    continue;
                }
            }

            if (existingMemberCoAppKey.isEmpty() || existingMemberDigitalProfileID.isEmpty() || digitalProfileId.equalsIgnoreCase(existingMemberDigitalProfileID)) {
                JSONObject input = new JSONObject();
                input.put("entityDefinitionCode", entityDefintionCode);
                Iterator keys = documentChecklistConfig.keys();

                while(true) {
                    JSONArray sectionData;
                    while(keys.hasNext()) {
                        String sectionName = (String)keys.next();
                        sectionData = documentChecklistConfig.getJSONArray(sectionName);
                        String entry;
                        if (sectionData.getJSONObject(0).optString("dataSourceType").equalsIgnoreCase("Application")) {
                            if (this.getEntityItemEntry(sectionName, applicationDetails, sectionName) == null) {
                                continue;
                            }

                            entry = this.getEntityItemEntry(sectionName, applicationDetails, sectionName).getString("entry");
                        } else if (sectionData.getJSONObject(0).optString("dataSourceType").equalsIgnoreCase("Company")) {
                            if (i != -1 && sectionData.getJSONObject(0).optString("name").equalsIgnoreCase("CompanyType")) {
                                input.put("CompanyType", "");
                                continue;
                            }

                            if (this.getEntityItemEntry(sectionName, applicationDetails, sectionName + "_Company") == null) {
                                continue;
                            }

                            entry = this.getEntityItemEntry(sectionName, applicationDetails, sectionName + "_Company").getString("entry");
                        } else if (this.getEntityItemEntry(sectionName, applicationDetails, sectionName + "_" + digitalProfileId) == null) {
                            if (this.getEntityItemEntry(sectionName, applicationDetails, sectionName) == null) {
                                continue;
                            }

                            entry = this.getEntityItemEntry(sectionName, applicationDetails, sectionName).getString("entry");
                        } else {
                            entry = this.getEntityItemEntry(sectionName, applicationDetails, sectionName + "_" + digitalProfileId).getString("entry");
                        }

                        Configuration config = Configuration.defaultConfiguration().addOptions(new Option[]{Option.SUPPRESS_EXCEPTIONS});
                        ReadContext context = JsonPath.using(config).parse(entry);
                        Iterator var25 = sectionData.iterator();

                        while(var25.hasNext()) {
                            Object object = var25.next();
                            JSONObject item = (JSONObject)object;
                            String name = item.getString("name");
                            String path = item.getString("path");
                            String type = item.getString("type");
                            if (type.equalsIgnoreCase("List")) {
                                List contextValue = (List)context.read(path, new Predicate[0]);
                                input.put(name, contextValue);
                            } else if (type.equalsIgnoreCase("Boolean")) {
                                Boolean contextValue = (Boolean)context.read(path, new Predicate[0]);
                                if (contextValue != null) {
                                    input.put(name, contextValue);
                                }
                            } else {
                                String contextValue = (String)context.read(path, new Predicate[0]);
                                if (contextValue != null && !contextValue.trim().isEmpty()) {
                                    input.put(name, contextValue);
                                }
                            }
                        }
                    }

                    inputs.put(input);
                    RulesEngineBusinessDelegate rulesEngineBusinessDelegate = (RulesEngineBusinessDelegate)DBPAPIAbstractFactoryImpl.getBusinessDelegate(RulesEngineBusinessDelegate.class);
                    sectionData = rulesEngineBusinessDelegate.getDocumentChecklistDecision(input, request);
                    resultObj.put(this.formatRulesResponse(sectionData).getJSONObject(0));
                    break;
                }
            }
        }

        Dataset dataset = CommonUtils.constructDatasetFromJSONArray(resultObj);
        dataset.setId("result");
        result.addDataset(dataset);
        return result;
    }

    private JSONObject getDocumentChecklistConfig(DataControllerRequest request) throws OnBoardingException {
        Map<String, Object> configParameters = new HashMap();
        configParameters.put("bundle_name", SessionManager.BUNDLE_NAME.retreiveFromSession(request));
        configParameters.put("config_key", "DOCUMENT_CHECKLIST_CONFIG");
        JSONObject documentChecklistConfig = this.fetchConfigurations(configParameters);
        if (documentChecklistConfig.has("Configuration") && documentChecklistConfig.getJSONObject("Configuration").length() == 0) {
            throw new OnBoardingException(ErrorCodeEnum.ERR_79017);
        } else {
            return new JSONObject(documentChecklistConfig.getJSONObject("Configuration").getString("value"));
        }
    }

    public JSONObject fetchConfigurations(Map<String, Object> configParameters) throws OnBoardingException {
        new JSONObject();

        try {
            JSONObject configValue = OnboardingServices.FETCH_CONFIGURATIONS.invokeServiceAndGetJSON((Map)null, configParameters);
            return configValue;
        } catch (DBPApplicationException var4) {
            logger.error(var4.getLocalizedMessage());
            throw new OnBoardingException(var4.getDBPError().getErrorCodeAsString(), var4.getDBPError().getErrorMessage());
        }
    }

    private JSONArray formatRulesResponse(JSONArray resultObj) {
        for(; resultObj.length() > 1; resultObj.remove(1)) {
            String str = (String)resultObj.getJSONObject(1).keySet().iterator().next();
            if (resultObj.getJSONObject(0).has(str)) {
                for(int i = 0; i < resultObj.getJSONObject(1).getJSONArray(str).length(); ++i) {
                    resultObj.getJSONObject(0).getJSONArray(str).put(resultObj.getJSONObject(1).getJSONArray(str).get(i));
                }
            } else {
                resultObj.getJSONObject(0).put(str, resultObj.getJSONObject(1).get(str));
            }
        }

        return resultObj;
    }

    public Result uploadDocumentForChecklist(String methodID, Object[] inputArray, DataControllerRequest request, DataControllerResponse response, JSONObject mulipleDataObj) throws Exception {
        JSONObject result = new JSONObject();
        String applicationId = SessionManager.APPLICATION_ID.retreiveFromSession(request);
        String entityDefintionCode = SessionManager.FORM_CODE.retreiveFromSession(request);
        if (applicationId != null && entityDefintionCode != null) {
            StorageBusinessDelegate storageBusinessDelegate = (StorageBusinessDelegate)DBPAPIAbstractFactoryImpl.getBusinessDelegate(StorageBusinessDelegate.class);
            DocumentBusinessDelegate documentBusinessDelegate = (DocumentBusinessDelegate)DBPAPIAbstractFactoryImpl.getBusinessDelegate(DocumentBusinessDelegate.class);
            new JSONObject();
            JSONObject requestPayload;
            if (mulipleDataObj != null) {
                requestPayload = mulipleDataObj;
            } else {
                requestPayload = OnboardingUtilities.getJSONFromRequest(request);
            }

            String userId = IdentityUtilities.getUserIdFromSession(request);
            if (entityDefintionCode.equalsIgnoreCase("Onboarding") || entityDefintionCode.equalsIgnoreCase("SMEOnboarding")) {
                JSONArray applicationDetails = this.fetchODMSDetails(entityDefintionCode, applicationId);
                JSONObject metadatObj = new JSONObject(this.getEntityItemEntry("MetaData", applicationDetails, "MetaData").getString("entry"));
                if (metadatObj != null && metadatObj.has("PrimaryApplicant")) {
                    userId = metadatObj.getString("PrimaryApplicant").split("_")[1];
                }
            }

            AuthenticationOnboarding authenticationOnboarding = AuthenticationOnboarding.getInstance();
            String jwtToken = authenticationOnboarding.generateDMSToken(request, "DocumentOwner", userId);
            JSONObject entryData = new JSONObject();
            String digitalProfileId = userId;
            String ownerSystemId = CommonUtils.getDMSOwnerSystemId(request, entityDefintionCode);
            if (requestPayload.has("Documents") && StringUtils.isNotBlank(requestPayload.get("Documents").toString())) {
                JSONArray documents = new JSONArray(requestPayload.get("Documents").toString());
                if (requestPayload.has("ApplicantType")) {
                    if (requestPayload.get("ApplicantType").toString().equalsIgnoreCase("Company")) {
                        digitalProfileId = applicationId + "_Company";
                    } else if (requestPayload.get("ApplicantType").toString().equalsIgnoreCase("Applicant")) {
                        digitalProfileId = IdentityUtilities.getUserIdFromSession(request);
                    } else if (requestPayload.get("ApplicantType").toString().equalsIgnoreCase("CoApplicant")) {
                        digitalProfileId = OnboardingUtilities.getDigitalProfileId(requestPayload, request);
                    }
                }

                JSONArray resultArray = new JSONArray();

                for(int i = 0; i < documents.length(); ++i) {
                    JSONObject document = documents.getJSONObject(i);
                    Result docUploadResult = documentBusinessDelegate.uploadDocument(digitalProfileId, document.getString("fileName"), jwtToken, document.getString("fileContents"), "journey-" + ownerSystemId + "-" + applicationId, ownerSystemId, document.getString("fileType"), applicationId);
                    if (docUploadResult.getOpstatusParamValue().equals("0")) {
                        entryData.put("documentId", docUploadResult.getParamValueByName("documentId"));
                        entryData.put("systemId", ownerSystemId);
                        entryData.put("roleId", "");
                        entryData.put("documentName", document.getString("fileName"));
                        entryData.put("documentType", document.getString("fileType"));
                        entryData.put("documentSize", (int)((double)document.getString("fileContents").length() * 0.75D / 1024.0D));
                        entryData.put("documentSource", "");
                        entryData.put("documentDescription", document.optString("fileInfo"));
                        entryData.put("documentClientId", document.optString("fileClientId"));
                        entryData.put("referenceId", digitalProfileId);
                        entryData.put("isChecked", requestPayload.optString("isChecked"));
                        entryData.put("documentUploadedDate", CommonUtils.getFormattedTimeStamp(new Date(), "yyyy-MM-dd"));
                        String var10004 = requestPayload.getString("Section");
                        JSONObject getDocResponse = storageBusinessDelegate.createDocument(entityDefintionCode, applicationId, entryData, var10004 + "_" + requestPayload.getString("Type") + "_" + docUploadResult.getParamValueByName("documentId"), "DocumentChecklist");
                        resultArray.put(getDocResponse);
                    } else if (StringUtils.isNotBlank(docUploadResult.getParamValueByName("errmsg"))) {
                        String[] docUploadErrorArray = docUploadResult.getParamValueByName("errmsg").split(":");
                        result.put("Error", Arrays.toString(docUploadErrorArray));
                    }
                }

                result.put("records", resultArray);
            }

            return JSONToResult.convert(result.toString());
        } else {
            throw new OnBoardingException(ErrorCodeEnum.ERR_71009);
        }
    }

    public Result uploadDocumentForChangeRequest(String methodID, Object[] inputArray, DataControllerRequest request, DataControllerResponse response) throws Exception {
        Result result = new Result();
        DocumentBusinessDelegate documentBusinessDelegate = (DocumentBusinessDelegate)DBPAPIAbstractFactoryImpl.getBusinessDelegate(DocumentBusinessDelegate.class);
        new JSONObject();
        JSONObject responseObj = new JSONObject();
        JSONObject requestPayload = OnboardingUtilities.getJSONFromRequest(request);
        JSONArray responseArray = new JSONArray();
        HashMap<String, Object> headerMap = new HashMap();
        headerMap.put("authorizationKey", ServerConfigurations.VIEWDOC_AUTH_KEY.getValueIfExists());
        headerMap.put("documentGroup", ServerConfigurations.DOCUMENTGROUP.getValueIfExists());
        headerMap.put("ownerSystemId", ServerConfigurations.OWNERSYSTEMID.getValueIfExists());
        if (requestPayload.has("Documents") && StringUtils.isNotBlank(requestPayload.get("Documents").toString())) {
            JSONArray documents = new JSONArray(requestPayload.get("Documents").toString());

            for(int iterator = 0; iterator < documents.length(); ++iterator) {
                JSONObject document = documents.getJSONObject(iterator);
                JSONObject entryData = new JSONObject();
                Result docUploadResult = documentBusinessDelegate.uploadDocument("123", document.getString("fileName"), headerMap.get("authorizationKey").toString(), document.getString("fileContents"), headerMap.get("documentGroup").toString(), headerMap.get("ownerSystemId").toString(), document.getString("fileType"), "123");
                if (!docUploadResult.getOpstatusParamValue().equals("0")) {
                    return docUploadResult;
                }

                entryData.put("docId", docUploadResult.getParamValueByName("documentId"));
                entryData.put("documentType", document.optString("fileTypeOfProof"));
                entryData.put("documentName", document.getString("fileName").lastIndexOf(".") > 0 ? document.getString("fileName").substring(0, document.getString("fileName").lastIndexOf(".")) : "");
                entryData.put("fileName", document.getString("fileName"));
                entryData.put("fileType", document.getString("fileType"));
                entryData.put("documentStatus", "Pending");
                entryData.put("documentSize", (int)((double)document.getString("fileContents").length() * 0.75D / 1024.0D));
                entryData.put("documentUploadedDate", CommonUtils.getFormattedTimeStamp(new Date(), "yyyy-MM-dd"));
                responseArray.put(entryData);
            }

            responseObj.put("supportingDocumentData", responseArray);
            responseObj.put("opstatus", 0);
            responseObj.put("httpStatusCode", 200);
            result = JSONToResult.convert(responseObj.toString());
        }

        return result;
    }

    public Result getDocumentChecklist(String methodID, Object[] inputArray, DataControllerRequest dcRequest, DataControllerResponse dcResponse) throws OnBoardingException {
        Result result = new Result();

        String entityDefintionCode;
        try {
            String applicationId = SessionManager.APPLICATION_ID.retreiveFromSession(dcRequest);
            entityDefintionCode = SessionManager.FORM_CODE.retreiveFromSession(dcRequest);
            StorageBusinessDelegate storageBusinessDelegate = (StorageBusinessDelegate)DBPAPIAbstractFactoryImpl.getBusinessDelegate(StorageBusinessDelegate.class);
            JSONArray entityItems = storageBusinessDelegate.getEntityItemsForTrackingCode(entityDefintionCode, applicationId);
            Boolean isExistingMemberCoAppFlow = false;
            if (dcRequest.containsKeyInRequest("isExistingMemberCoAppFlow")) {
                isExistingMemberCoAppFlow = true;
            }

            JSONObject resultObj = new JSONObject();
            JSONArray responseObj = this.getDocumentsData(entityDefintionCode, applicationId, entityItems, isExistingMemberCoAppFlow, dcRequest);
            resultObj.put("Documents", responseObj);
            return JSONToResult.convert(resultObj.toString());
        } catch (OnBoardingException var13) {
            result = var13.updateResultObject(result);
        } catch (Exception var14) {
            entityDefintionCode = "Error in DocumentChecklist : getDocumentChecklist : " + var14.toString();
            logger.error(entityDefintionCode);
            result = ErrorCodeEnum.ERR_73100.updateResultObject(result);
            String var10002 = var14.getMessage();
            result.addParam("Error", var10002 + " " + var14.getStackTrace()[0].toString() + " " + var14.getStackTrace()[1].toString() + " " + var14.getStackTrace()[2].toString() + " " + var14.getStackTrace()[3].toString() + " " + var14.getStackTrace()[4].toString());
        }

        return result;
    }

    private JSONArray getDocumentsData(String entityDefintionCode, String applicationId, JSONArray entityItems, Boolean isExistingMemberCoAppFlow, DataControllerRequest request) {
        JSONArray resultArray = new JSONArray();
        JSONArray documentsEntity = this.getEntityDataByEntityDefName(entityItems, "DocumentChecklist");
        if (documentsEntity.length() == 0) {
            return resultArray;
        } else {
            JSONObject result = new JSONObject();

            JSONObject resultObject;
            String loggedInDigitalProfileId;
            for(int i = 0; i < documentsEntity.length(); ++i) {
                JSONObject curDoc = new JSONObject();
                JSONObject curObj = documentsEntity.getJSONObject(i);
                resultObject = new JSONObject(curObj.getString("entry"));
                loggedInDigitalProfileId = curObj.getString("name").split("_")[0];
                if (result.has(resultObject.getString("referenceId"))) {
                    curDoc = result.getJSONObject(resultObject.getString("referenceId"));
                }

                JSONArray curDocArr = new JSONArray();
                if (curDoc.has(loggedInDigitalProfileId)) {
                    curDocArr = curDoc.getJSONArray(loggedInDigitalProfileId);
                }

                JSONObject documentRecord = new JSONObject();
                JSONObject fileObj = new JSONObject();
                fileObj.put("documentName", resultObject.getString("documentName"));
                documentRecord.put("fileObj", fileObj);
                documentRecord.put("documentId", resultObject.getString("documentId"));
                documentRecord.put("documentDescription", resultObject.getString("documentDescription"));
                documentRecord.put("referenceId", resultObject.getString("referenceId"));
                documentRecord.put("clientDocID", resultObject.getString("documentClientId"));
                documentRecord.put("status", "removable");
                documentRecord.put("name", curObj.getString("name"));
                documentRecord.put("documentType", curObj.getString("name").split("_")[1]);
                curDocArr.put(documentRecord);
                curDoc.put(loggedInDigitalProfileId, curDocArr);
                result.put(resultObject.getString("referenceId"), curDoc);
            }

            List<String> customerIds = this.getListOfCustomerIds(applicationId, entityItems);
            Iterator var18 = customerIds.iterator();

            while(true) {
                while(true) {
                    String customerId;
                    do {
                        do {
                            if (!var18.hasNext()) {
                                return resultArray;
                            }

                            customerId = (String)var18.next();
                        } while(!result.has(customerId));
                    } while(!(result.get(customerId) instanceof JSONObject));

                    resultObject = new JSONObject();
                    if (customerId.contains("_Company")) {
                        resultObject.put(this.getEntityDataByName(entityItems, "PersonalInfo_Company").optString("CompanyName"), result.getJSONObject(customerId));
                        break;
                    }

                    JSONObject personalInfoObj;
                    if (isExistingMemberCoAppFlow) {
                        loggedInDigitalProfileId = IdentityUtilities.getUserIdFromSession(request);
                        if (!loggedInDigitalProfileId.equalsIgnoreCase(customerId)) {
                            continue;
                        }
                    } else {
                        personalInfoObj = this.getEntityDataByName(entityItems, "ApplicantMetaData_" + customerId);
                        if (personalInfoObj.has("IsExistingCustomer") && (personalInfoObj.optString("IsExistingCustomer").equalsIgnoreCase("Yes") || personalInfoObj.optString("IsExistingCustomer").equalsIgnoreCase("true"))) {
                            continue;
                        }
                    }

                    personalInfoObj = this.getEntityDataByName(entityItems, "PersonalInfo_" + customerId);
                    resultObject.put(personalInfoObj.optString("FirstName") + " " + personalInfoObj.optString("LastName"), result.getJSONObject(customerId));
                    break;
                }

                resultArray.put(resultObject);
            }
        }
    }

    public List<String> getListOfCustomerIds(String applicationId, JSONArray entityItems) {
        List<String> customerIds = new ArrayList();
        JSONObject metadataJSON = this.getEntityDataByName(entityItems, "MetaData");
        if (metadataJSON.length() != 0) {
            if (metadataJSON.has("Company") && !metadataJSON.getString("Company").equalsIgnoreCase("")) {
                customerIds.add(applicationId + "_Company");
            }

            if (metadataJSON.get("PrimaryApplicant") instanceof String && !metadataJSON.getString("PrimaryApplicant").equalsIgnoreCase("")) {
                customerIds.add(metadataJSON.getString("PrimaryApplicant").split("_")[1]);
            }

            JSONArray coApplicants = metadataJSON.getJSONArray("CoApplicants");

            for(int i = 0; i < coApplicants.length(); ++i) {
                if (!coApplicants.get(i).toString().equalsIgnoreCase("")) {
                    customerIds.add(coApplicants.get(i).toString().split("_")[1]);
                }
            }
        }

        return customerIds;
    }

    public JSONObject getEntityDataByName(JSONArray entityItems, String entityName) {
        if (StringUtils.isEmpty(entityName)) {
            return new JSONObject();
        } else {
            Optional<Object> metadataEntity = StreamSupport.stream(entityItems.spliterator(), true).filter((item) -> {
                return ((JSONObject)item).getString("name").equalsIgnoreCase(entityName);
            }).findFirst();
            if (metadataEntity.isPresent()) {
                JSONObject entityJSON = (JSONObject)metadataEntity.get();
                return entityName != "UserAction" && entityName != "Notes" ? new JSONObject(entityJSON.getString("entry")) : entityJSON;
            } else {
                return new JSONObject();
            }
        }
    }

    private JSONArray getEntityDataByEntityDefName(JSONArray entityItems, String entityType) {
        JSONArray result = new JSONArray();
        if (StringUtils.isEmpty(entityType)) {
            return result;
        } else {
            List<Object> objList = (List)StreamSupport.stream(entityItems.spliterator(), true).filter((item) -> {
                return ((JSONObject)item).getString("entityItemDefinitionName").equalsIgnoreCase(entityType);
            }).collect(Collectors.toList());
            if (objList.size() == 0) {
                return result;
            } else {
                Iterator var5 = objList.iterator();

                while(var5.hasNext()) {
                    Object objItem = var5.next();
                    result.put((JSONObject)objItem);
                }

                return result;
            }
        }
    }

    public Result downloadDocument(String methodID, Object[] inputArray, DataControllerRequest request, DataControllerResponse response) throws Exception {
        String applicationId = SessionManager.APPLICATION_ID.retreiveFromSession(request);
        String entityDefintionCode = SessionManager.FORM_CODE.retreiveFromSession(request);
        String userId = IdentityUtilities.getUserIdFromSession(request);
        if (entityDefintionCode.equalsIgnoreCase("Onboarding") || entityDefintionCode.equalsIgnoreCase("SMEOnboarding")) {
            JSONArray applicationDetails = this.fetchODMSDetails(entityDefintionCode, applicationId);
            JSONObject metadatObj = new JSONObject(this.getEntityItemEntry("MetaData", applicationDetails, "MetaData").getString("entry"));
            if (metadatObj != null && metadatObj.has("PrimaryApplicant")) {
                userId = metadatObj.getString("PrimaryApplicant").split("_")[1];
            }
        }

        String ownerSystemId = CommonUtils.getDMSOwnerSystemId(request, entityDefintionCode);
        AuthenticationOnboarding authenticationOnboarding = AuthenticationOnboarding.getInstance();
        String jwtToken = authenticationOnboarding.generateDMSToken(request, "DocumentOwner", userId);
        DocumentBusinessDelegate documentBusinessDelegate = (DocumentBusinessDelegate)DBPAPIAbstractFactoryImpl.getBusinessDelegate(DocumentBusinessDelegate.class);
        JSONObject responseObj = documentBusinessDelegate.downloadDocument(request.getParameter("documentId"), ownerSystemId, "journey-" + ownerSystemId + "-" + applicationId, jwtToken);
        return JSONToResult.convert(responseObj.toString());
    }

    public Result deleteDocument(String methodID, Object[] inputArray, DataControllerRequest request, DataControllerResponse response) throws Exception {
        String ownerSystemId = CommonUtils.getDMSOwnerSystemId(request, SessionManager.FORM_CODE.retreiveFromSession(request));
        AuthenticationOnboarding authenticationOnboarding = AuthenticationOnboarding.getInstance();
        String userId = IdentityUtilities.getUserIdFromSession(request);
        String jwtToken = authenticationOnboarding.generateDMSToken(request, "DocumentOwner", userId);
        DocumentBusinessDelegate documentBusinessDelegate = (DocumentBusinessDelegate)DBPAPIAbstractFactoryImpl.getBusinessDelegate(DocumentBusinessDelegate.class);
        JSONObject responseObj = documentBusinessDelegate.deleteDocument(request.getParameter("Name").split("_")[2], ownerSystemId, "journey-" + ownerSystemId + "-" + SessionManager.APPLICATION_ID.retreiveFromSession(request), jwtToken);
        StorageBusinessDelegate storageBusinessDelegate = (StorageBusinessDelegate)DBPAPIAbstractFactoryImpl.getBusinessDelegate(StorageBusinessDelegate.class);
        storageBusinessDelegate.deleteEntityItemWithName(this.constructDeletePayload(request, request.getParameter("Name")));
        return JSONToResult.convert(responseObj.toString());
    }

    private JSONObject constructDeletePayload(DataControllerRequest request, String name) {
        JSONObject payload = new JSONObject();
        payload.put("entityDefinitionCode", SessionManager.FORM_CODE.retreiveFromSession(request));
        payload.put("deletedReason", "COMPLETED");
        payload.put("entityDefinitionName", name);
        payload.put("trackingCode", SessionManager.APPLICATION_ID.retreiveFromSession(request));
        payload.put("type", "DOCUMENT");
        return payload;
    }

    public void updateApplicantMetaData(DataControllerRequest request) throws OnBoardingException, Exception {
        StorageBusinessDelegate storageBusinessDelegate = (StorageBusinessDelegate)DBPAPIAbstractFactoryImpl.getBusinessDelegate(StorageBusinessDelegate.class);
        String entityDefintionCode = SessionManager.FORM_CODE.retreiveFromSession(request);
        String applicationId = SessionManager.APPLICATION_ID.retreiveFromSession(request);
        String digitalProfileId = IdentityUtilities.getCustomerIdFromSession(request);
        JSONObject payload = storageBusinessDelegate.getSection(entityDefintionCode, applicationId, "ApplicantMetaData_" + digitalProfileId);
        payload.put("SectionProgress", "Done");

        try {
            storageBusinessDelegate.updateApplicantMetaData(entityDefintionCode, applicationId, payload, "Documents");
        } catch (Exception var8) {
            logger.error("Error in updateApplicantMetaData : " + var8.getMessage());
        }

    }

    public JSONArray fetchODMSDetails(String entityDefinitionCode, String applicationId) throws DBPApplicationException, Exception {
        StorageBusinessDelegate storageBusinessDelegate = (StorageBusinessDelegate)DBPAPIAbstractFactoryImpl.getBusinessDelegate(StorageBusinessDelegate.class);
        return storageBusinessDelegate.getApplication(entityDefinitionCode, applicationId);
    }

    public JSONObject getEntityItemEntry(String entityItemDefinitionName, JSONArray entityItems, String name) {
        JSONObject entry = new JSONObject();
        Optional<Object> s = StreamSupport.stream(entityItems.spliterator(), true).filter((item) -> {
            return StringUtils.equals(((JSONObject)item).optString("name"), name) && StringUtils.equals(((JSONObject)item).optString("entityItemDefinitionName"), entityItemDefinitionName);
        }).findFirst();
        if (!s.isPresent()) {
            return null;
        } else {
            JSONObject obj = (JSONObject)s.get();
            entry.put("entry", obj.optString("entry"));
            entry.put("id", obj.optString("id"));
            entry.put("type", obj.optString("type"));
            entry.put("version", obj.optString("version"));
            entry.put("name", obj.optString("name"));
            entry.put("entryHash", obj.optString("entryHash"));
            return entry;
        }
    }

    public Result getDocumentsData(String methodId, Object[] inputArray, DataControllerRequest request, DataControllerResponse response) throws DBPApplicationException, Exception {
        Result result = new Result();
        String fulfilmentId = "";
        Map<String, Object> inputParams = (HashMap) inputArray[1];
        String getAmount = inputParams.get("customerAmount").toString();
        String getCustomerAction = inputParams.get("customerAction").toString();

        try {
            JSONArray inputs = new JSONArray();
            new JSONArray();
            new JSONArray();
            String partyId = request.containsKeyInRequest("partyId") ? request.getParameter("partyId") : "";
            String companyId = request.containsKeyInRequest("companyId") ? request.getParameter("companyId") : "";
            new JSONObject();
            new JSONObject();
            JSONArray fulfilmentsArray = new JSONArray();
            String entityDefintionCode = SessionManager.FORM_CODE.retreiveFromSession(request);
            String applicationId = SessionManager.APPLICATION_ID.retreiveFromSession(request);
            String ownerSystemId = CommonUtils.getDMSOwnerSystemId(request, entityDefintionCode);
            String existingMemberCoAppKey = "";
            String existingMemberDigitalProfileID = "";
            String ownerType = "";
            String entityID = "";
            Boolean existingMemberFlow = false;
            if (request.containsKeyInRequest("ExistingMemberCoAppKey")) {
                existingMemberFlow = true;
                existingMemberCoAppKey = request.getParameter("ExistingMemberCoAppKey");
                String digitalProfileIds = SessionManager.DIGITAL_PROFILE_IDS.retreiveFromSession(request);
                if (StringUtils.isNotEmpty(digitalProfileIds)) {
                    JSONObject coApplicantMapping = new JSONObject(digitalProfileIds);
                    if (coApplicantMapping.has(existingMemberCoAppKey)) {
                        existingMemberDigitalProfileID = coApplicantMapping.getString(existingMemberCoAppKey);
                    }
                }
            }

            JSONObject documentChecklistConfig = this.getDocumentChecklistConfig(request);
            JSONArray applicationDetails = this.fetchODMSDetails(entityDefintionCode, applicationId);
            JSONObject metadatObj = new JSONObject(this.getEntityItemEntry("MetaData", applicationDetails, "MetaData").getString("entry"));
            int noOfCoapplicants = entityDefintionCode.equalsIgnoreCase("SMEOnboarding") ? metadatObj.getJSONArray("CoApplicants").length() + 1 : metadatObj.getJSONArray("CoApplicants").length();
            String userId = IdentityUtilities.getUserIdFromSession(request);
            if (metadatObj != null && metadatObj.has("PrimaryApplicant")) {
                userId = metadatObj.getString("PrimaryApplicant").split("_")[1];
            }

            AuthenticationOnboarding authenticationOnboarding = AuthenticationOnboarding.getInstance();
            String jwtToken = authenticationOnboarding.generateDMSToken(request, "DocumentOwner", userId);
            JSONArray resultObj = new JSONArray();

            for(int i = -1; i < noOfCoapplicants; ++i) {
                String digitalProfileId;
                if (entityDefintionCode.equalsIgnoreCase("SMEOnboarding")) {
                    if (i == -1) {
                        digitalProfileId = applicationId + "_Company";
                    } else {
                        digitalProfileId = i == 0 ? metadatObj.getString("PrimaryApplicant").split("_")[1] : metadatObj.getJSONArray("CoApplicants").getString(i - 1).split("_")[1];
                    }
                } else {
                    digitalProfileId = i == -1 ? metadatObj.getString("PrimaryApplicant").split("_")[1] : metadatObj.getJSONArray("CoApplicants").getString(i).split("_")[1];
                }

                if ((!entityDefintionCode.equalsIgnoreCase("SMEOnboarding") || i != -1 && i != 0) && i > -1 && existingMemberCoAppKey.isEmpty()) {
                    String coApplicantSectionName = "ApplicantMetaData_" + digitalProfileId;
                    JSONObject coApplicantEntry = new JSONObject(this.getEntityItemEntry("ApplicantMetaData", applicationDetails, coApplicantSectionName).getString("entry"));
                    if (coApplicantEntry.has("IsExistingCustomer") && (coApplicantEntry.optString("IsExistingCustomer").equalsIgnoreCase("Yes") || coApplicantEntry.optString("IsExistingCustomer").equalsIgnoreCase("true")) || coApplicantEntry.has("IsInvited") && coApplicantEntry.optString("IsInvited").equalsIgnoreCase("Yes")) {
                        continue;
                    }
                }
                if (existingMemberCoAppKey.isEmpty() || existingMemberDigitalProfileID.isEmpty() || digitalProfileId.equalsIgnoreCase(existingMemberDigitalProfileID)) {
                    JSONObject input = new JSONObject();
                    if (getAmount !=null && !getAmount.equalsIgnoreCase("")){
                            Double doubleValue = Double.parseDouble(getAmount.replaceAll(",", ""));
                            if (doubleValue>49999){
                                input.put("customerAmount", "Yes");
                            }

                    }
                    if (getCustomerAction.equalsIgnoreCase("Yes") && getCustomerAction !=null && getCustomerAction !=""){
                        input.put("customerAction", "Yes");
                    }
                    input.put("entityDefinitionCode", entityDefintionCode);
                    Iterator keys = documentChecklistConfig.keys();

                    while(true) {
                        JSONArray sectionData;
                        JSONObject personalInfoObj;
                        String name;
                        label240:
                        while(keys.hasNext()) {
                            String sectionName = (String)keys.next();
                            sectionData = documentChecklistConfig.getJSONArray(sectionName);
                            String entry;
                            if (sectionData.getJSONObject(0).optString("dataSourceType").equalsIgnoreCase("Application")) {
                                if (this.getEntityItemEntry(sectionName, applicationDetails, sectionName) == null) {
                                    continue;
                                }

                                entry = this.getEntityItemEntry(sectionName, applicationDetails, sectionName).getString("entry");
                            } else if (sectionData.getJSONObject(0).optString("dataSourceType").equalsIgnoreCase("Company")) {
                                if (i != -1 && sectionData.getJSONObject(0).optString("name").equalsIgnoreCase("CompanyType")) {
                                    input.put("CompanyType", "");
                                    continue;
                                }

                                if (this.getEntityItemEntry(sectionName, applicationDetails, sectionName + "_Company") == null) {
                                    continue;
                                }

                                entry = this.getEntityItemEntry(sectionName, applicationDetails, sectionName + "_Company").getString("entry");
                            } else if (this.getEntityItemEntry(sectionName, applicationDetails, sectionName + "_" + digitalProfileId) == null) {
                                if (this.getEntityItemEntry(sectionName, applicationDetails, sectionName) == null) {
                                    continue;
                                }

                                entry = this.getEntityItemEntry(sectionName, applicationDetails, sectionName).getString("entry");
                            } else {
                                entry = this.getEntityItemEntry(sectionName, applicationDetails, sectionName + "_" + digitalProfileId).getString("entry");
                            }

                            ReadContext context = JsonPath.parse(entry);
                            Iterator var39 = sectionData.iterator();

                            while(true) {
                                while(true) {
                                    if (!var39.hasNext()) {
                                        continue label240;
                                    }

                                    Object object = var39.next();
                                    personalInfoObj = (JSONObject)object;
                                    name = personalInfoObj.getString("name");
                                    String path = personalInfoObj.getString("path");
                                    String type = personalInfoObj.getString("type");
                                    if (type.equalsIgnoreCase("List")) {
                                        List contextValue = (List)context.read(path, new Predicate[0]);
                                        input.put(name, contextValue);
                                    } else if (type.equalsIgnoreCase("Boolean")) {
                                        if (!name.equalsIgnoreCase("PropertyIdentified") || existingMemberCoAppKey.isEmpty() || existingMemberDigitalProfileID.isEmpty()) {
                                            Boolean contextValue = (Boolean)context.read(path, new Predicate[0]);
                                            if (contextValue != null) {
                                                input.put(name, contextValue);
                                            }
                                        }
                                    } else {
                                        String contextValue = "";
                                        if (name.equalsIgnoreCase("PropertySubType")) {
                                            contextValue = entry.contains(name) ? (String)context.read(path, new Predicate[0]) : "";
                                        } else {
                                            contextValue = (String)context.read(path, new Predicate[0]);
                                        }

                                        if (contextValue != null && !contextValue.trim().isEmpty()) {
                                            input.put(name, contextValue);
                                        }
                                    }
                                }
                            }
                        }

                        inputs.put(input);
                        RulesEngineBusinessDelegate rulesEngineBusinessDelegate = (RulesEngineBusinessDelegate)DBPAPIAbstractFactoryImpl.getBusinessDelegate(RulesEngineBusinessDelegate.class);
                        sectionData = rulesEngineBusinessDelegate.getDocumentChecklistDecision(input, request);
                        JSONObject dmnRulesRes = this.formatRulesResponse(sectionData).getJSONObject(0);
                        resultObj.put(dmnRulesRes);
                        JSONArray DMNDocResponse = formatDMNResponse(dmnRulesRes);
                        String desc = "";
                        userId = digitalProfileId;
                        ownerType = "newProspect";
                        if ((!entityDefintionCode.equalsIgnoreCase("SMEOnboarding") || i != 0) && (!entityDefintionCode.equalsIgnoreCase("Onboarding") || i != -1)) {
                            if (entityDefintionCode.equalsIgnoreCase("SMEOnboarding") && i == -1) {
                                if (!companyId.isEmpty()) {
                                    userId = companyId;
                                    ownerType = "Party";
                                }
                            } else if (existingMemberFlow && !partyId.isEmpty()) {
                                userId = partyId;
                                ownerType = "Party";
                            }
                        } else if (!partyId.isEmpty()) {
                            userId = partyId;
                            ownerType = "Party";
                        }

                        if (entityDefintionCode.equalsIgnoreCase("SMEOnboarding") && i == -1) {
                            entityID = "ApplicantMetaData_Company";
                        } else {
                            entityID = "ApplicantMetaData_" + digitalProfileId;
                        }

                        JSONObject appMetadataObj = new JSONObject(this.getEntityItemEntry("ApplicantMetaData", applicationDetails, entityID).toString());
                        Boolean isPrimary = false;
                        if (entityDefintionCode.equalsIgnoreCase("SMEOnboarding") && i == 0 || entityDefintionCode.equalsIgnoreCase("Onboarding") && i == -1) {
                            isPrimary = true;
                        }

                        if (isPrimary || existingMemberFlow) {
                            this.deleteDocEntities(entityDefintionCode, applicationDetails, applicationId, existingMemberFlow);
                        }

                        JSONObject fulfilmentCreation = this.createFulfilment(DMNDocResponse, request, userId, applicationId, ownerSystemId, desc, ownerType, jwtToken);
                        if (!fulfilmentCreation.has("appFulfilmentId")) {
                            String errorMsg = "Error in getDocumentsData: fulfilment creation failed";
                            logger.error(errorMsg);
                            OnBoardingException onBoardingException = new OnBoardingException(ErrorCodeEnum.ERR_82100);
                            throw onBoardingException;
                        }

                        fulfilmentId = fulfilmentCreation.optString("appFulfilmentId");
                        this.updateFulfilmentIdToODMS(appMetadataObj.optString("id"), new JSONObject(appMetadataObj.optString("entry")), fulfilmentId);
                        JSONObject fulfilmentDetails = this.getfulfilmentDetails(fulfilmentId, request, userId, applicationId, ownerSystemId, jwtToken);
                        personalInfoObj = this.getEntityDataByName(applicationDetails, "PersonalInfo_" + digitalProfileId);
                        String var10000 = personalInfoObj.optString("FirstName");
                        name = var10000 + " " + personalInfoObj.optString("LastName");
                        JSONObject a = new JSONObject();
                        a.put(name, this.formatGetfulfilmentDetails(fulfilmentDetails, digitalProfileId, isPrimary, applicationDetails, request));
                        a.put("appFulfilmentId", fulfilmentId);
                        fulfilmentsArray.put(a);
                        break;
                    }
                }
            }

            Dataset dataset = CommonUtils.constructDatasetFromJSONArray(resultObj);
            Dataset dataset1 = CommonUtils.constructDatasetFromJSONArray(fulfilmentsArray);
            dataset.setId("DMNResponse");
            dataset1.setId("FulfilmentResponse");
            result.addDataset(dataset);
            result.addDataset(dataset1);
            return result;
        } catch (OnBoardingException var46) {
            result = var46.updateResultObject(result);
            return result;
        } catch (Exception var47) {
            String errorMsg = "Error in getDocumentsMethod : getPersonalInfoData : " + var47.toString();
            logger.error(errorMsg);
            result = ErrorCodeEnum.ERR_82102.updateResultObject(result);
            return result;
        }
    }

    private JSONObject deleteDocEntities(String entityDefCode, JSONArray applicationDetails, String appID, Boolean existingMemberFlow) throws DBPApplicationException, Exception {
        ArrayList<String> docList = new ArrayList();
        JSONObject result = new JSONObject();
        JSONObject metaData = this.getEntityDataByName(applicationDetails, "MetaData");
        JSONArray coAppArray = metaData.getJSONArray("CoApplicants");
        List<String> coApplicantsList = new ArrayList();

        for(int i = 0; i < coAppArray.length(); ++i) {
            coApplicantsList.add(coAppArray.getString(i));
        }

        List<String> existingorInvitedCoAppList = new ArrayList();

        int i;
        JSONObject entity;
        JSONObject payload;
        for(i = 0; i < applicationDetails.length(); ++i) {
            entity = applicationDetails.getJSONObject(i);
            if (coApplicantsList.contains(entity.optString("name"))) {
                payload = new JSONObject(entity.getString("entry"));
                boolean isInvitedCoApp = payload.has("IsInvited") && payload.optString("IsInvited").equalsIgnoreCase("Yes");
                if (payload.has("IsExistingCustomer") && (payload.optString("IsExistingCustomer").equalsIgnoreCase("Yes") || payload.optString("IsExistingCustomer").equalsIgnoreCase("true")) || isInvitedCoApp) {
                    existingorInvitedCoAppList.add(entity.optString("name").split("_")[1]);
                }
            }
        }

        for(i = 0; i < applicationDetails.length(); ++i) {
            entity = applicationDetails.getJSONObject(i);
            if (entity.optString("entityItemDefinitionName").equalsIgnoreCase("DocumentChecklist")) {
                String category = entity.optString("name").split("_")[0];
                JSONObject entry = new JSONObject(entity.getString("entry"));
                String referenceId = entry.optString("referenceId");
                category = category.contains("-") ? category.split("-")[0] : category;
                if (entityDocList.contains(category)) {
                    if (!existingMemberFlow && !existingorInvitedCoAppList.contains(referenceId)) {
                        docList.add(entity.optString("name"));
                    } else if (existingMemberFlow && existingorInvitedCoAppList.contains(referenceId)) {
                        docList.add(entity.optString("name"));
                    }
                }
            }
        }

        StorageBusinessDelegate storageBusinessDelegate;
        for(Iterator var17 = docList.iterator(); var17.hasNext(); result = storageBusinessDelegate.deleteEntityItemWithName(payload)) {
            String doc = (String)var17.next();
            payload = new JSONObject();
            payload.put("entityDefinitionCode", entityDefCode);
            payload.put("deletedReason", "COMPLETED");
            payload.put("entityDefinitionName", doc);
            payload.put("trackingCode", appID);
            payload.put("type", "DOCUMENT");
            storageBusinessDelegate = (StorageBusinessDelegate)DBPAPIAbstractFactoryImpl.getBusinessDelegate(StorageBusinessDelegate.class);
        }

        return result;
    }

    private void updateFulfilmentIdToODMS(String entityItemId, JSONObject metadatObj, String fulfilmentID) throws DBPApplicationException, Exception {
        metadatObj.put("AppFulfilmentId", fulfilmentID);
        StorageBackendDelegate storageBackendDelegate = (StorageBackendDelegate)DBPAPIAbstractFactoryImpl.getBackendDelegate(StorageBackendDelegate.class);
        storageBackendDelegate.updateEntityItemById(entityItemId, metadatObj.toString());
    }

    private JSONObject formatGetfulfilmentDetails(JSONObject fulfilmentDetails, String referenceId, Boolean isPrimary, JSONArray applicationDetails, DataControllerRequest request) throws Exception {
        JSONObject fullfillmentJSON = new JSONObject();
        JSONArray requiredDocs = fulfilmentDetails.getJSONArray("requirementFulfilments");
        String evidenceCategory = "";

        int k;
        String fileSize;
        for(int i = 0; i < requiredDocs.length(); ++i) {
            JSONArray keysArray = new JSONArray();
            evidenceCategory = requiredDocs.getJSONObject(i).optString("evidenceCategory");
            new JSONArray();
            JSONArray applicationEvidences = requiredDocs.getJSONObject(i).getJSONArray("applicationEvidences");
            if (applicationEvidences.length() > 0) {
                JSONArray acceptedBefore = null;

                int n;
                String evidenceExpiryDate;
                for(n = 0; n < applicationEvidences.length(); ++n) {
                    evidenceExpiryDate = applicationEvidences.getJSONObject(n).optString("evidenceExpiryDate");
                    if (applicationEvidences.getJSONObject(n).optString("status").equals("acceptedBefore") && validationForExpiryDate(evidenceExpiryDate)) {
                        acceptedBefore = new JSONArray();
                        acceptedBefore.put(applicationEvidences.getJSONObject(n));
                    }
                }

                if (acceptedBefore == null) {
                    new JSONArray();
                    acceptedBefore = applicationEvidences;
                }

                for(n = 0; n < acceptedBefore.length(); ++n) {
                    evidenceExpiryDate = acceptedBefore.getJSONObject(n).optString("evidenceExpiryDate");
                    JSONObject payload = new JSONObject();
                    JSONArray documentIds = acceptedBefore.getJSONObject(n).getJSONArray("documents");

                    for(k = 0; k < documentIds.length(); ++k) {
                        String docIds = (String)documentIds.getJSONObject(k).get("documentId");
                        String fileName = (String)documentIds.getJSONObject(k).get("fileName");
                        fileSize = documentIds.getJSONObject(k).get("fileSize").toString();
                        JSONObject fileObj = new JSONObject();
                        if (acceptedBefore.getJSONObject(n).optString("status").equals("acceptedBefore") && validationForExpiryDate(evidenceExpiryDate)) {
                            String evidenceType = acceptedBefore.getJSONObject(n).optString("evidenceType");
                            payload.put("name", evidenceCategory + "_" + evidenceType + "_" + docIds);
                            boolean validExpiryDate = validationForExpiryDate(evidenceExpiryDate);
                            if (validExpiryDate) {
                                payload.put("isEvidenceValid", "valid");
                            }

                            fileObj.put("documentName", fileName);
                            payload.put("fileObj", fileObj);
                            payload.put("documentDescription", fileName);
                            payload.put("documentType", acceptedBefore.getJSONObject(n).optString("evidenceType"));
                            payload.put("documentId", docIds);
                            payload.put("referenceId", referenceId);
                            payload.put("clientDocID", "");
                            payload.put("fileSize", fileSize);
                            payload.put("appFulfilmentId", fulfilmentDetails.optString("appFulfilmentId"));
                            payload.put("status", "removable");
                            payload.put("appEvidenceId", acceptedBefore.getJSONObject(n).optString("appEvidenceId"));
                            payload.put("evidenceId", acceptedBefore.getJSONObject(n).optString("evidenceId"));
                            if (fullfillmentJSON.has(evidenceCategory)) {
                                keysArray = fullfillmentJSON.getJSONArray(evidenceCategory);
                            }

                            keysArray.put(payload);
                            break;
                        }

                        if (acceptedBefore.getJSONObject(n).optString("status").equals("rejected")) {
                            payload.put("isEvidenceValid", "inValid");
                            payload.put("documentDescription", "");
                            payload.put("documentType", acceptedBefore.getJSONObject(n).optString("evidenceType"));
                            payload.put("documentId", docIds);
                            payload.put("referenceId", referenceId);
                            payload.put("clientDocID", "");
                            payload.put("appFulfilmentId", fulfilmentDetails.optString("appFulfilmentId"));
                            payload.put("appEvidenceId", acceptedBefore.getJSONObject(n).optString("appEvidenceId"));
                            payload.put("evidenceId", acceptedBefore.getJSONObject(n).optString("evidenceId"));
                            if (fullfillmentJSON.has(evidenceCategory)) {
                                keysArray = fullfillmentJSON.getJSONArray(evidenceCategory);
                            }

                            keysArray.put(payload);
                        } else if (acceptedBefore.getJSONObject(n).optString("status").equals("acceptedBefore") && !validationForExpiryDate(evidenceExpiryDate)) {
                            payload.put("isEvidenceValid", "inValid");
                            payload.put("documentDescription", "");
                            payload.put("documentType", acceptedBefore.getJSONObject(n).optString("evidenceType"));
                            payload.put("documentId", docIds);
                            payload.put("referenceId", referenceId);
                            payload.put("clientDocID", "");
                            payload.put("appFulfilmentId", fulfilmentDetails.optString("appFulfilmentId"));
                            payload.put("appEvidenceId", acceptedBefore.getJSONObject(n).optString("appEvidenceId"));
                            payload.put("evidenceId", acceptedBefore.getJSONObject(n).optString("evidenceId"));
                            if (fullfillmentJSON.has(evidenceCategory)) {
                                keysArray = fullfillmentJSON.getJSONArray(evidenceCategory);
                            }

                            keysArray.put(payload);
                        } else {
                            payload.put("isEvidenceValid", "NA");
                            if (fullfillmentJSON.has(evidenceCategory)) {
                                keysArray = fullfillmentJSON.getJSONArray(evidenceCategory);
                            }

                            keysArray.put(payload);
                        }
                    }

                    fullfillmentJSON.put(evidenceCategory, keysArray);
                }
            }
        }

        if (isPrimary) {
            JSONArray documentsEntities = this.getEntityDataByEntityDefName(applicationDetails, "DocumentChecklist");
            String applicationId = SessionManager.APPLICATION_ID.retreiveFromSession(request);
            String entityDefintionCode = SessionManager.FORM_CODE.retreiveFromSession(request);
            String userId = IdentityUtilities.getUserIdFromSession(request);
            String ownerSystemId = CommonUtils.getDMSOwnerSystemId(request, entityDefintionCode);
            AuthenticationOnboarding authenticationOnboarding = AuthenticationOnboarding.getInstance();
            String jwtToken = authenticationOnboarding.generateDMSToken(request, "DocumentOwner", userId);
            DocumentBusinessDelegate documentBusinessDelegate = (DocumentBusinessDelegate)DBPAPIAbstractFactoryImpl.getBusinessDelegate(DocumentBusinessDelegate.class);
            if (documentsEntities.length() > 0) {
                for(k = 0; k < documentsEntities.length(); ++k) {
                    JSONObject curObj = documentsEntities.getJSONObject(k);
                    JSONObject documentEntry = new JSONObject(curObj.getString("entry"));
                    fileSize = curObj.getString("name").split("_")[0];
                    if (!entityDocList.contains(fileSize)) {
                        JSONArray curDocArr = new JSONArray();
                        if (fullfillmentJSON.has(fileSize)) {
                            curDocArr = fullfillmentJSON.getJSONArray(fileSize);
                        }

                        JSONObject documentRecord = new JSONObject();
                        JSONObject fileObj = new JSONObject();
                        JSONObject responseObj = documentBusinessDelegate.downloadDocument(documentEntry.getString("documentId"), ownerSystemId, "journey-" + ownerSystemId + "-" + applicationId, jwtToken);
                        fileObj.put("documentName", documentEntry.getString("documentName"));
                        fileObj.put("type", documentEntry.optString("documentType"));
                        fileObj.put("content", responseObj.getString("content"));
                        documentRecord.put("fileObj", fileObj);
                        documentRecord.put("documentId", documentEntry.getString("documentId"));
                        documentRecord.put("documentDescription", documentEntry.getString("documentDescription"));
                        documentRecord.put("referenceId", documentEntry.getString("referenceId"));
                        documentRecord.put("clientDocID", documentEntry.getString("documentClientId"));
                        documentRecord.put("status", "removable");
                        documentRecord.put("name", curObj.getString("name"));
                        documentRecord.put("documentType", curObj.getString("name").split("_")[1]);
                        curDocArr.put(documentRecord);
                        fullfillmentJSON.put(fileSize, curDocArr);
                    }
                }
            }
        }

        return fullfillmentJSON;
    }

    public static boolean validationForExpiryDate(String evidenceExpiryDate) {
        SimpleDateFormat sdformat = new SimpleDateFormat("yyyy-MM-dd");
        boolean value = false;
        Date formatEvidenceExpiryDate = new Date();

        try {
            formatEvidenceExpiryDate = sdformat.parse(evidenceExpiryDate);
        } catch (ParseException var9) {
            logger.error("Error occured in parsing the expiry date:" + var9.toString());
        }

        Date currentDate = new Date();
        String formattedCurrentDate = sdformat.format(currentDate);
        Date presentDate = new Date();

        try {
            presentDate = sdformat.parse(formattedCurrentDate);
        } catch (ParseException var8) {
            logger.error("Error occured in parsing the expiry date:" + var8.toString());
        }

        if (formatEvidenceExpiryDate.compareTo(presentDate) >= 0) {
            value = true;
        } else if (formatEvidenceExpiryDate.compareTo(presentDate) < 0) {
            value = false;
        }

        return value;
    }

    private JSONObject createFulfilment(JSONArray dMNDocResponse, DataControllerRequest request, String userId, String appID, String ownerSystemId, String desc, String ownerType, String jwtToken) throws OnBoardingException, Exception {
        try {
            DocumentBusinessDelegate documentBusinessDelegate = (DocumentBusinessDelegate)DBPAPIAbstractFactoryImpl.getBusinessDelegate(DocumentBusinessDelegate.class);
            Result result = documentBusinessDelegate.createFulfilment(appID, userId, ownerType, desc, dMNDocResponse, jwtToken, "journey-" + ownerSystemId + "-" + appID, ownerSystemId);
            JSONObject fulfilmentDetails = new JSONObject();
            if (result.getOpstatusParamValue().equals("0")) {
                fulfilmentDetails.put("appFulfilmentId", result.getParamValueByName("appFulfilmentId"));
                return fulfilmentDetails;
            } else {
                String[] errorArray = result.getParamValueByName("errmsg").split(":");
                fulfilmentDetails.put("Error", Arrays.toString(errorArray));
                throw new OnBoardingException(ErrorCodeEnum.ERR_82100);
            }
        } catch (OnBoardingException var13) {
            throw var13;
        } catch (Exception var14) {
            OnBoardingException onBoardingException = new OnBoardingException(ErrorCodeEnum.ERR_82100);
            throw onBoardingException;
        }
    }

    private JSONObject getfulfilmentDetails(String fulfilmentId, DataControllerRequest request, String userId, String appID, String ownerSystemId, String jwtToken) throws OnBoardingException, Exception {
        try {
            DocumentBusinessDelegate documentBusinessDelegate = (DocumentBusinessDelegate)DBPAPIAbstractFactoryImpl.getBusinessDelegate(DocumentBusinessDelegate.class);
            String result = documentBusinessDelegate.getFulfilment(fulfilmentId, userId, jwtToken, "journey-" + ownerSystemId + "-" + appID, ownerSystemId);
            JSONObject fulfilmentDetails = new JSONObject(result);
            return fulfilmentDetails;
        } catch (OnBoardingException var10) {
            throw var10;
        } catch (Exception var11) {
            OnBoardingException onBoardingException = new OnBoardingException(ErrorCodeEnum.ERR_82101);
            throw onBoardingException;
        }
    }

    private static JSONArray formatDMNResponse(JSONObject result) {
        JSONArray requirements = new JSONArray();
        JSONArray key = result.names();

        for(int i = 0; i < key.length(); ++i) {
            String keys = key.optString(i);
            if (entityDocList.contains(keys)) {
                JSONArray value = result.getJSONArray(keys);

                for(int j = 0; j < value.length(); ++j) {
                    JSONObject docArray = value.getJSONObject(j);
                    JSONObject payload = new JSONObject();
                    if (value.length() == 1) {
                        payload.put("key", keys);
                    } else {
                        payload.put("key", keys + "-" + docArray.optString("documentTitle"));
                    }

                    payload.put("name", "");
                    payload.put("description", "");
                    payload.put("evidenceCategory", keys);
                    JSONArray validEvidenceTypes = new JSONArray();
                    String[] documentTypes = docArray.optString("documentTypes").split(",");

                    for(int k = 0; k < documentTypes.length; ++k) {
                        String temp = documentTypes[k].replaceAll("\"", "");
                        temp = temp.replace("[", "");
                        temp = temp.replace("]", "");
                        validEvidenceTypes.put(temp);
                    }

                    payload.put("validEvidenceTypes", validEvidenceTypes);
                    JSONObject properties = new JSONObject();
                    if (docArray.optString("attribute").equals("mandatory")) {
                        properties.put("isRequired", "true");
                    } else {
                        properties.put("isRequired", "false");
                    }

                    payload.put("properties", properties);
                    requirements.put(payload);
                }
            }
        }

        return requirements;
    }

    public Result deleteEvidence(String methodID, Object[] inputArray, DataControllerRequest request, DataControllerResponse response) throws OnBoardingException, Exception {
        Result result = new Result();

        String entityDefintionCode;
        try {
            String ownerSystemId = CommonUtils.getDMSOwnerSystemId(request, SessionManager.FORM_CODE.retreiveFromSession(request));
            entityDefintionCode = SessionManager.FORM_CODE.retreiveFromSession(request);
            String appID = SessionManager.APPLICATION_ID.retreiveFromSession(request);
            AuthenticationOnboarding authenticationOnboarding = AuthenticationOnboarding.getInstance();
            String userId = IdentityUtilities.getUserIdFromSession(request);
            if (entityDefintionCode.equalsIgnoreCase("Onboarding") || entityDefintionCode.equalsIgnoreCase("SMEOnboarding")) {
                JSONArray applicationDetails = this.fetchODMSDetails(entityDefintionCode, appID);
                JSONObject metadatObj = new JSONObject(this.getEntityItemEntry("MetaData", applicationDetails, "MetaData").getString("entry"));
                if (metadatObj != null && metadatObj.has("PrimaryApplicant")) {
                    userId = metadatObj.getString("PrimaryApplicant").split("_")[1];
                }
            }

            String jwtToken = authenticationOnboarding.generateDMSToken(request, "DocumentOwner", userId);
            String appFulfilmentId = request.getParameter("appFulfilmentId");
            String appEvidenceId = request.getParameter("appEvidenceId");
            DocumentBusinessDelegate documentBusinessDelegate = (DocumentBusinessDelegate)DBPAPIAbstractFactoryImpl.getBusinessDelegate(DocumentBusinessDelegate.class);
            Result resp = documentBusinessDelegate.deleteEvidence(appFulfilmentId, appEvidenceId, userId, jwtToken, "journey-" + ownerSystemId + "-" + appID, ownerSystemId);
            if (request.containsKeyInRequest("Name")) {
                List<String> names = new ArrayList(Arrays.asList(request.getParameter("Name").split(",")));
                StorageBusinessDelegate storageBusinessDelegate = (StorageBusinessDelegate)DBPAPIAbstractFactoryImpl.getBusinessDelegate(StorageBusinessDelegate.class);

                for(int j = 0; j < names.size(); ++j) {
                    storageBusinessDelegate.deleteEntityItemWithName(this.constructDeletePayload(request, ((String)names.get(j)).toString()));
                }
            }

            return resp;
        } catch (OnBoardingException var19) {
            result = var19.updateResultObject(result);
            return result;
        } catch (Exception var20) {
            entityDefintionCode = "Error in deleteEvidence : " + var20.toString();
            logger.error(entityDefintionCode);
            result = ErrorCodeEnum.ERR_79019.updateResultObject(result);
            return result;
        }
    }

    public Result useEvidence(String methodID, Object[] inputArray, DataControllerRequest request, DataControllerResponse response) throws OnBoardingException, Exception {
        Result result = new Result();

        String entityDefintionCode;
        try {
            String ownerSystemId = CommonUtils.getDMSOwnerSystemId(request, SessionManager.FORM_CODE.retreiveFromSession(request));
            entityDefintionCode = SessionManager.FORM_CODE.retreiveFromSession(request);
            String appID = SessionManager.APPLICATION_ID.retreiveFromSession(request);
            AuthenticationOnboarding authenticationOnboarding = AuthenticationOnboarding.getInstance();
            String userId = IdentityUtilities.getUserIdFromSession(request);
            if (entityDefintionCode.equalsIgnoreCase("Onboarding") || entityDefintionCode.equalsIgnoreCase("SMEOnboarding")) {
                JSONArray applicationDetails = this.fetchODMSDetails(entityDefintionCode, appID);
                JSONObject metadatObj = new JSONObject(this.getEntityItemEntry("MetaData", applicationDetails, "MetaData").getString("entry"));
                if (metadatObj != null && metadatObj.has("PrimaryApplicant")) {
                    userId = metadatObj.getString("PrimaryApplicant").split("_")[1];
                }
            }

            String jwtToken = authenticationOnboarding.generateDMSToken(request, "DocumentOwner", userId);
            String appFulfilmentId = request.getParameter("appFulfilmentId");
            String appEvidenceId = request.getParameter("appEvidenceId");
            String action = request.getParameter("action");
            DocumentBusinessDelegate documentBusinessDelegate = (DocumentBusinessDelegate)DBPAPIAbstractFactoryImpl.getBusinessDelegate(DocumentBusinessDelegate.class);
            Result resp = documentBusinessDelegate.useEvidence(appFulfilmentId, appEvidenceId, userId, jwtToken, "journey-" + ownerSystemId + "-" + appID, ownerSystemId, action);
            return resp;
        } catch (OnBoardingException var17) {
            result = var17.updateResultObject(result);
            return result;
        } catch (Exception var18) {
            entityDefintionCode = "Error in useEvidence : " + var18.toString();
            logger.error(entityDefintionCode);
            result = ErrorCodeEnum.ERR_79019.updateResultObject(result);
            return result;
        }
    }

    public Result submitEvidence(String methodID, Object[] inputArray, DataControllerRequest request, DataControllerResponse response, JSONObject mulipleDataObj) throws OnBoardingException, Exception {
        Result result = new Result();
        JSONObject resultObj = new JSONObject();
        JSONArray resultArray = new JSONArray();

        String entityDefintionCode;
        try {
            String ownerSystemId = CommonUtils.getDMSOwnerSystemId(request, SessionManager.FORM_CODE.retreiveFromSession(request));
            entityDefintionCode = SessionManager.FORM_CODE.retreiveFromSession(request);
            String appID = SessionManager.APPLICATION_ID.retreiveFromSession(request);
            logger.error(">>>>>applicationIDNEW"+appID);
            AuthenticationOnboarding authenticationOnboarding = AuthenticationOnboarding.getInstance();
            String userId = IdentityUtilities.getUserIdFromSession(request);
            String evidenceId = "";
            new JSONObject();
            Boolean newEvidence = true;
            new JSONObject();
            JSONObject requestPayload;
            if (mulipleDataObj != null) {
                requestPayload = this.formatSubmitEvidencePayload(mulipleDataObj);
            } else {
                requestPayload = OnboardingUtilities.getJSONFromRequest(request);
            }

            String appFulfilmentId = requestPayload.optString("appFulfilmentId");
            if (requestPayload.has("appEvidenceId")) {
                evidenceId = requestPayload.optString("appEvidenceId");
                newEvidence = false;
            }

            String evidenceType = requestPayload.optString("evidenceType");
            String forRequirements = requestPayload.optString("forRequirements");
            JSONArray documentsArray = new JSONArray(requestPayload.optString("documents"));
            JSONObject payload;
            if (entityDefintionCode.equalsIgnoreCase("Onboarding") || entityDefintionCode.equalsIgnoreCase("SMEOnboarding")) {
                JSONArray applicationDetails = this.fetchODMSDetails(entityDefintionCode, appID);
                payload = new JSONObject(this.getEntityItemEntry("MetaData", applicationDetails, "MetaData").getString("entry"));
                if (payload != null && payload.has("PrimaryApplicant")) {
                    userId = payload.getString("PrimaryApplicant").split("_")[1];
                }
            }

            String digitalProfileId = userId;
            payload = new JSONObject();
            payload.put("Index", requestPayload.optString("Index"));
            if (requestPayload.has("ApplicantType")) {
                if (requestPayload.optString("ApplicantType").toString().equalsIgnoreCase("Company")) {
                    digitalProfileId = appID + "_Company";
                } else if (requestPayload.optString("ApplicantType").toString().equalsIgnoreCase("Applicant")) {
                    digitalProfileId = IdentityUtilities.getUserIdFromSession(request);
                } else if (requestPayload.optString("ApplicantType").toString().equalsIgnoreCase("CoApplicant")) {
                    digitalProfileId = OnboardingUtilities.getDigitalProfileId(payload, request);
                }
            }

            String jwtToken = authenticationOnboarding.generateDMSToken(request, "DocumentOwner", userId);

            for(int i = 0; i < documentsArray.length(); ++i) {
                JSONObject documentJSON = documentsArray.getJSONObject(i);
                String fileContent = documentJSON.optString("fileContent");
                String fileName = documentJSON.optString("fileName");
                String fileType = documentJSON.optString("fileName").split("[.]")[1];
                String documentClientId = documentJSON.optString("documentClientId");
                String resp = null;
                DocumentBusinessDelegate documentBusinessDelegate = (DocumentBusinessDelegate)DBPAPIAbstractFactoryImpl.getBusinessDelegate(DocumentBusinessDelegate.class);
                if (i == 0 && newEvidence) {
                    resp = documentBusinessDelegate.submitEvidence(appFulfilmentId, digitalProfileId, jwtToken, "journey-" + ownerSystemId + "-" + appID, ownerSystemId, fileContent, evidenceType, forRequirements, fileName);
                } else if (!evidenceId.isEmpty()) {
                    resp = documentBusinessDelegate.addEvidence(appFulfilmentId, evidenceId, digitalProfileId, jwtToken, "journey-" + ownerSystemId + "-" + appID, ownerSystemId, fileContent, evidenceType, forRequirements, fileName);
                }

                JSONObject respJSON = new JSONObject(resp);
                JSONObject entryData = new JSONObject();
                JSONArray evidence = respJSON.getJSONArray("evidenceList");
                JSONObject evidenceJSON = evidence.getJSONObject(0);
                evidenceId = evidenceJSON.optString("appEvidenceId");
                StorageBusinessDelegate storageBusinessDelegate = (StorageBusinessDelegate)DBPAPIAbstractFactoryImpl.getBusinessDelegate(StorageBusinessDelegate.class);
                if (respJSON.optString("opstatus").equals("0")) {
                    entryData.put("isExistingEvidence", "no");
                    entryData.put("documentId", evidenceJSON.optString("documentId"));
                    entryData.put("appEvidenceId", evidenceJSON.optString("evidenceId"));
                    entryData.put("applicationEvidenceId", evidenceJSON.optString("appEvidenceId"));
                    entryData.put("documentDescription", documentJSON.optString("fileInfo"));
                    entryData.put("systemId", ownerSystemId);
                    entryData.put("roleId", "");
                    entryData.put("documentName", fileName);
                    entryData.put("documentType", "application/" + fileType);
                    entryData.put("documentSize", (int)((double)fileContent.length() * 0.75D / 1024.0D));
                    entryData.put("documentSource", "");
                    entryData.put("referenceId", digitalProfileId);
                    entryData.put("documentClientId", documentClientId);
                    entryData.put("documentUploadedDate", CommonUtils.getFormattedTimeStamp(new Date(), "yyyy-MM-dd"));
                    forRequirements = forRequirements.contains("-") ? forRequirements.split("-")[0] : forRequirements;
                    JSONObject getDocResponse = storageBusinessDelegate.createDocument(entityDefintionCode, appID, entryData, forRequirements + "_" + evidenceType + "_" + evidenceJSON.get("documentId"), "DocumentChecklist");
                    resultArray.put(getDocResponse);
                } else if (StringUtils.isNotBlank(respJSON.getString("errmsg"))) {
                    String[] docUploadErrorArray = respJSON.getString("errmsg").split(":");
                    result.addParam("Error", Arrays.toString(docUploadErrorArray));
                    break;
                }
            }

            resultObj.put("records", resultArray);
            resultObj.put("appFulfilmentId", appFulfilmentId);
        } catch (OnBoardingException var38) {
            result = var38.updateResultObject(result);
            return result;
        } catch (Exception var39) {
            entityDefintionCode = "Error in SubmitEvidence: " + var39.toString();
            logger.error(entityDefintionCode);
            result = ErrorCodeEnum.ERR_79019.updateResultObject(result);
            return result;
        }

        return JSONToResult.convert(resultObj.toString());
    }

    private JSONObject formatSubmitEvidencePayload(JSONObject mulipleDataObj) {
        JSONObject payload = new JSONObject();
        JSONArray documentsPayload = new JSONArray();
        JSONArray documents = mulipleDataObj.getJSONArray("Documents");

        for(int i = 0; i < documents.length(); ++i) {
            JSONObject documentPayload = new JSONObject();
            JSONObject document = documents.getJSONObject(i);
            documentPayload.put("fileName", document.optString("fileName"));
            documentPayload.put("documentClientId", document.optString("fileClientId"));
            documentPayload.put("fileInfo", document.optString("fileInfo"));
            documentPayload.put("fileContent", document.optString("fileContents"));
            documentsPayload.put(documentPayload);
        }

        payload.put("documents", documentsPayload);
        payload.put("appFulfilmentId", mulipleDataObj.optString("appFulfilmentId"));
        payload.put("evidenceType", mulipleDataObj.optString("Type"));
        payload.put("forRequirements", mulipleDataObj.optString("Section"));
        payload.put("ApplicantType", mulipleDataObj.optString("ApplicantType"));
        payload.put("Index", mulipleDataObj.optString("Index"));
        return payload;
    }

    public Result acceptEvidences(DataControllerRequest request, JSONArray evidences) {
        Result result = new Result();

        String userId;
        try {
            AuthenticationOnboarding authenticationOnboarding = AuthenticationOnboarding.getInstance();
            userId = IdentityUtilities.getUserIdFromSession(request);
            String ownerSystemId = CommonUtils.getDMSOwnerSystemId(request, SessionManager.FORM_CODE.retreiveFromSession(request));
            String appID = SessionManager.APPLICATION_ID.retreiveFromSession(request);
            String jwtToken = authenticationOnboarding.generateDMSToken(request, "DocumentOwner", userId);
            String action = "agree";
            String entityDefintionCode = SessionManager.FORM_CODE.retreiveFromSession(request);
            List<String> updatedEvidences = new ArrayList();
            DocumentBusinessDelegate documentBusinessDelegate = (DocumentBusinessDelegate)DBPAPIAbstractFactoryImpl.getBusinessDelegate(DocumentBusinessDelegate.class);

            for(int i = 0; i < evidences.length(); ++i) {
                JSONObject fulfilmentJSON = evidences.getJSONObject(i);
                String fulfilmentId = fulfilmentJSON.optString("fulfilmentId");
                JSONArray evidenceList = fulfilmentJSON.getJSONArray("evidences");

                for(int j = 0; j < evidenceList.length(); ++j) {
                    if (!evidenceList.get(j).equals((Object)null) && evidenceList.get(j) != null) {
                        JSONObject evidenceJSON = evidenceList.getJSONObject(j);
                        if (!updatedEvidences.contains(evidenceJSON.optString("appEvidenceId"))) {
                            documentBusinessDelegate.useEvidence(fulfilmentId, evidenceJSON.optString("appEvidenceId"), userId, jwtToken, "journey-" + ownerSystemId + "-" + appID, ownerSystemId, action);
                            updatedEvidences.add(evidenceJSON.optString("appEvidenceId"));
                        }

                        StorageBusinessDelegate storageBusinessDelegate = (StorageBusinessDelegate)DBPAPIAbstractFactoryImpl.getBusinessDelegate(StorageBusinessDelegate.class);
                        JSONObject entryData = new JSONObject();
                        String fileType = evidenceJSON.optString("documentName").split("[.]")[1];
                        String referenceId = evidenceJSON.optString("referenceId").isEmpty() ? userId : evidenceJSON.optString("referenceId");
                        entryData.put("isExistingEvidence", "yes");
                        entryData.put("documentId", evidenceJSON.optString("documentId"));
                        entryData.put("appEvidenceId", evidenceJSON.optString("evidenceId"));
                        entryData.put("applicationEvidenceId", evidenceJSON.optString("appEvidenceId"));
                        entryData.put("documentDescription", evidenceJSON.optString("documentName"));
                        entryData.put("systemId", ownerSystemId);
                        entryData.put("roleId", "");
                        entryData.put("documentName", evidenceJSON.optString("documentName"));
                        entryData.put("documentType", "application/" + fileType);
                        entryData.put("documentSize", evidenceJSON.optString("documentSize"));
                        entryData.put("documentSource", "");
                        entryData.put("referenceId", referenceId);
                        entryData.put("documentClientId", evidenceJSON.optString("documentClientId"));
                        entryData.put("documentUploadedDate", CommonUtils.getFormattedTimeStamp(new Date(), "yyyy-MM-dd"));
                        storageBusinessDelegate.createDocument(entityDefintionCode, appID, entryData, evidenceJSON.optString("name"), "DocumentChecklist");
                    }
                }
            }

            return result;
        } catch (Exception var23) {
            userId = "Error in acceptEvidences: " + var23.toString();
            logger.error(userId);
            result = ErrorCodeEnum.ERR_75101.updateResultObject(result);
            return result;
        }
    }
}
