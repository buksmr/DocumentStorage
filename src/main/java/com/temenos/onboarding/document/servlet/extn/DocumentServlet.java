package com.temenos.onboarding.document.servlet.extn;

import com.dbp.core.api.APIImplementationTypes;
import com.dbp.core.api.factory.BusinessDelegateFactory;
import com.dbp.core.api.factory.ResourceFactory;
import com.dbp.core.api.factory.impl.DBPAPIAbstractFactoryImpl;
import com.konylabs.middleware.servlet.IntegrationCustomServlet;
import com.temenos.onboarding.document.mapper.DocumentBusinessDelegateMapper;
import com.temenos.onboarding.document.mapper.DocumentResourcesMapper;
import com.temenos.onboarding.document.mapper.extn.DocumentResourcesMapperExtn;
import com.temenos.onboarding.utilities.OnboardingThreadExecutor;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;

@IntegrationCustomServlet(
        servletName = "DocumentServlet",
        urlPatterns = {"DocumentServlet"}
)
public class DocumentServlet extends HttpServlet {
    private static final long serialVersionUID = -2198682326030156595L;

    public void init() throws ServletException {
        ((ResourceFactory)DBPAPIAbstractFactoryImpl.getInstance().getFactoryInstance(ResourceFactory.class)).registerResourceMappings(new DocumentResourcesMapperExtn(), APIImplementationTypes.EXTENSION);
        ((BusinessDelegateFactory)DBPAPIAbstractFactoryImpl.getInstance().getFactoryInstance(BusinessDelegateFactory.class)).registerBusinessDelegateMappings(new DocumentBusinessDelegateMapper(), APIImplementationTypes.BASE);
    }

    public void destroy() {
        try {
            OnboardingThreadExecutor.getExecutor().shutdownExecutor();
        } catch (Exception var2) {
        }

    }
}
