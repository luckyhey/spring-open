package net.floodlightcontroller.restserver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;

import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.Server;
import org.restlet.data.Protocol;
import org.restlet.data.Reference;
import org.restlet.data.Status;
import org.restlet.ext.jackson.JacksonRepresentation;
import org.restlet.representation.Representation;
import org.restlet.routing.Filter;
import org.restlet.routing.Router;
import org.restlet.routing.Template;
import org.restlet.service.StatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RestApiServer
        implements IFloodlightModule, IRestApiService {
    protected final static Logger logger = LoggerFactory.getLogger(RestApiServer.class);
    protected List<RestletRoutable> restlets;
    protected FloodlightModuleContext fmlContext;
    protected int restPort = 8080;
    protected String numThreads = null;

    // ***********
    // Application
    // ***********

    protected class RestApplication extends Application {
        protected Context context;

        public RestApplication() {
            super(new Context());
            this.context = getContext();
        }

        @Override
        public Restlet createInboundRoot() {
            Router baseRouter = new Router(context);
            baseRouter.setDefaultMatchingMode(Template.MODE_STARTS_WITH);
            for (RestletRoutable rr : restlets) {
                baseRouter.attach(rr.basePath(), rr.getRestlet(context));
            }

            Filter slashFilter = new Filter() {
                @Override
                protected int beforeHandle(Request request, Response response) {
                    Reference ref = request.getResourceRef();
                    String originalPath = ref.getPath();
                    if (originalPath.contains("//")) {
                        String newPath = originalPath.replaceAll("/+", "/");
                        ref.setPath(newPath);
                    }
                    return Filter.CONTINUE;
                }

            };
            slashFilter.setNext(baseRouter);

            return slashFilter;
        }

        public void run(FloodlightModuleContext fmlContext, int restPort) {
            setStatusService(new StatusService() {
                @Override
                public Representation getRepresentation(Status status,
                                                        Request request,
                                                        Response response) {
                    return new JacksonRepresentation<Status>(status);
                }
            });

            // Add everything in the module context to the rest
            for (Class<? extends IFloodlightService> s : fmlContext.getAllServices()) {
                if (logger.isTraceEnabled()) {
                    logger.trace("Adding {} for service {} into context",
                            s.getCanonicalName(), fmlContext.getServiceImpl(s));
                }
                context.getAttributes().put(s.getCanonicalName(),
                        fmlContext.getServiceImpl(s));
            }

            // Start listening for REST requests
            try {
                final Component component = new Component();
                Server server = component.getServers().add(Protocol.HTTP, restPort);
                if (numThreads != null) {
                    logger.debug("Setting number of REST API threads to {}", numThreads);
                    server.getContext().getParameters().add("defaultThreads", numThreads);
                }
                component.getDefaultHost().attach(this);
                component.start();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    // ***************
    // IRestApiService
    // ***************

    @Override
    public void addRestletRoutable(RestletRoutable routable) {
        restlets.add(routable);
    }

    @Override
    public void run() {
        if (logger.isDebugEnabled()) {
            StringBuffer sb = new StringBuffer();
            sb.append("REST API routables: ");
            for (RestletRoutable routable : restlets) {
                sb.append(routable.getClass().getSimpleName());
                sb.append(" (");
                sb.append(routable.basePath());
                sb.append("), ");
            }
            logger.debug(sb.toString());
        }

        RestApplication restApp = new RestApplication();
        restApp.run(fmlContext, restPort);
    }

    // *****************
    // IFloodlightModule
    // *****************

    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleServices() {
        Collection<Class<? extends IFloodlightService>> services =
                new ArrayList<Class<? extends IFloodlightService>>(1);
        services.add(IRestApiService.class);
        return services;
    }

    @Override
    public Map<Class<? extends IFloodlightService>, IFloodlightService>
    getServiceImpls() {
        Map<Class<? extends IFloodlightService>,
                IFloodlightService> m =
                new HashMap<Class<? extends IFloodlightService>,
                        IFloodlightService>();
        m.put(IRestApiService.class, this);
        return m;
    }

    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
        // We don't have any
        return null;
    }

    @Override
    public void init(FloodlightModuleContext context)
            throws FloodlightModuleException {
        // This has to be done here since we don't know what order the
        // startUp methods will be called
        this.restlets = new ArrayList<RestletRoutable>();
        this.fmlContext = context;

        // read our config options
        Map<String, String> configOptions = context.getConfigParams(this);
        String port = configOptions.get("port");
        if (port != null) {
            restPort = Integer.parseInt(port);
        }
        logger.debug("REST port set to {}", restPort);

        String numThreads = configOptions.get("dispatcherthreads");
        if (numThreads != null) {
            this.numThreads = numThreads;
        }
    }

    @Override
    public void startUp(FloodlightModuleContext Context) {
        // no-op
    }
}