package com.temenos.onboarding.document.mapper.extn;



import com.dbp.core.api.DBPAPIMapper;
import com.dbp.core.api.Resource;
import com.temenos.onboarding.document.mapper.DocumentResourcesMapper;
import com.temenos.onboarding.document.resouce.impl.extn.DocumentChecklistResourceImplExtn;
import com.temenos.onboarding.document.resource.api.DocumentChecklistResource;
import com.temenos.onboarding.document.resource.impl.DocumentChecklistResourceImpl;
import java.util.HashMap;
import java.util.Map;

public class DocumentResourcesMapperExtn extends DocumentResourcesMapper {
    public Map<Class<? extends Resource>, Class<? extends Resource>> getAPIMappings() {
        Map<Class<? extends Resource>, Class<? extends Resource>> map = new HashMap();
        map.put(DocumentChecklistResource.class, DocumentChecklistResourceImplExtn.class);
        return map;
    }
}
