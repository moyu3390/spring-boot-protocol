package com.github.netty.protocol;

import com.github.netty.core.AbstractProtocol;
import com.github.netty.core.util.IOUtil;
import com.github.netty.protocol.servlet.*;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpConstants;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;

import javax.net.ssl.SSLEngine;
import javax.servlet.Filter;
import javax.servlet.Servlet;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * HttpServlet protocol registry
 * @author wangzihao
 *  2018/11/11/011
 */
public class HttpServletProtocol extends AbstractProtocol {
    private final ServletContext servletContext;
    private SslContext sslContext;
    private SslContextBuilder sslContextBuilder;
    private ChannelHandler servletHandler;
    private int maxContentLength = 5 * 1024 * 1024;
    private int maxInitialLineLength = 4096;
    private int maxHeaderSize = 8192;
    private int maxChunkSize = 5 * 1024 * 1024;

    public HttpServletProtocol(Executor executor, ServletContext servletContext){
        this.servletContext = servletContext;
        this.servletHandler = new ServletChannelHandler(servletContext,executor);
    }

    @Override
    public void onServerStart() throws Exception {
        ServletEventListenerManager listenerManager = servletContext.getServletEventListenerManager();
        if(listenerManager.hasServletContextListener()){
            listenerManager.onServletContextInitialized(new ServletContextEvent(servletContext));
        }

        initFilter(servletContext);
        initServlet(servletContext);
    }

    @Override
    public void onServerStop() {
        ServletEventListenerManager listenerManager = servletContext.getServletEventListenerManager();
        if(listenerManager.hasServletContextListener()){
            listenerManager.onServletContextDestroyed(new ServletContextEvent(servletContext));
        }

        destroyFilter();
        destroyServlet();
    }

    /**
     * Initialization filter
     * @param servletContext
     */
    protected void initFilter(ServletContext servletContext) throws ServletException {
        Map<String, ServletFilterRegistration> servletFilterRegistrationMap = servletContext.getFilterRegistrations();
        for(ServletFilterRegistration registration : servletFilterRegistrationMap.values()){
            registration.getFilter().init(registration.getFilterConfig());
            registration.setInitParameter("_init","true");
        }
    }

    /**
     * Initialize the servlet
     * @param servletContext
     */
    protected void initServlet(ServletContext servletContext) throws ServletException {
        List<ServletRegistration> servletRegistrations = new ArrayList<>(servletContext.getServletRegistrations().values());
        servletRegistrations.sort(Comparator.comparingInt(ServletRegistration::getLoadOnStartup));
        for(ServletRegistration registration : servletRegistrations){
            registration.getServlet().init(registration.getServletConfig());
            registration.setInitParameter("_init","true");
        }
    }

    /**
     * Destruction filter
     */
    protected void destroyFilter(){
        Map<String, ServletFilterRegistration> servletRegistrationMap = servletContext.getFilterRegistrations();
        for(ServletFilterRegistration registration : servletRegistrationMap.values()){
            Filter filter = registration.getFilter();
            if(filter == null) {
                continue;
            }
            String initFlag = registration.getInitParameter("_init");
            if("true".equals(initFlag)){
                filter.destroy();
            }
        }
    }

    /**
     * Destruction servlet
     */
    protected void destroyServlet(){
        Map<String, ServletRegistration> servletRegistrationMap = servletContext.getServletRegistrations();
        for(ServletRegistration registration : servletRegistrationMap.values()){
            Servlet servlet = registration.getServlet();
            if(servlet == null) {
                continue;
            }
            String initFlag = registration.getInitParameter("_init");
            if("true".equals(initFlag)){
                servlet.destroy();
            }
        }
    }

    @Override
    public boolean canSupport(ByteBuf msg) {
        int protocolEndIndex = IOUtil.indexOf(msg, HttpConstants.LF);
        if(protocolEndIndex < 9){
            return false;
        }

        if(msg.getByte(protocolEndIndex - 9) == 'H'
                && msg.getByte(protocolEndIndex - 8) == 'T'
                && msg.getByte(protocolEndIndex - 7) == 'T'
                &&  msg.getByte(protocolEndIndex - 6) == 'P'){
            return true;
        }
        return false;
    }

    @Override
    public void addPipeline(Channel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();
        if (sslContextBuilder != null) {
            if(sslContext == null) {
                sslContext = sslContextBuilder.build();
            }
            SSLEngine engine = sslContext.newEngine(ch.alloc());
            pipeline.addLast("SSL", new SslHandler(engine,true));
        }

        //HTTP encoding decoding
        pipeline.addLast("HttpCodec", new HttpServerCodec(maxInitialLineLength, maxHeaderSize, maxChunkSize, false));

        //HTTP request aggregation, set the maximum message value to 5M
        pipeline.addLast("Aggregator", new HttpObjectAggregator(maxContentLength));

        //The content of compression
//                    pipeline.addLast("ContentCompressor", new HttpContentCompressor());
//                pipeline.addLast("ContentDecompressor", new HttpContentDecompressor());

        //A business scheduler that lets the corresponding Servlet handle the request
        pipeline.addLast("Servlet", servletHandler);
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public String getProtocolName() {
        String name = "http";
        if(sslContextBuilder != null){
            name = name.concat("/https");
        }
        return name;
    }

    public ServletContext getServletContext() {
        return servletContext;
    }

    public SslContextBuilder getSslContextBuilder() {
        return sslContextBuilder;
    }

    public void setSslContextBuilder(SslContextBuilder sslContextBuilder) {
        this.sslContextBuilder = sslContextBuilder;
    }

    public void setMaxContentLength(int maxContentLength) {
        this.maxContentLength = maxContentLength;
    }

    public void setMaxInitialLineLength(int maxInitialLineLength) {
        this.maxInitialLineLength = maxInitialLineLength;
    }

    public void setMaxHeaderSize(int maxHeaderSize) {
        this.maxHeaderSize = maxHeaderSize;
    }

    public void setMaxChunkSize(int maxChunkSize) {
        this.maxChunkSize = maxChunkSize;
    }
}
