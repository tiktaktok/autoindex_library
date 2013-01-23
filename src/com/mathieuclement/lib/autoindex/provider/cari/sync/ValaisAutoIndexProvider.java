package com.mathieuclement.lib.autoindex.provider.cari.sync;

import com.mathieuclement.lib.autoindex.provider.common.captcha.CaptchaHandler;
import org.apache.http.HttpHost;

public class ValaisAutoIndexProvider extends CariAutoIndexProvider {

    private HttpHost httpHost = new HttpHost("193.247.117.81");
    //private HttpHost httpHost = new HttpHost("www.vs.ch");

    public ValaisAutoIndexProvider(CaptchaHandler captchaHandler) {
        super(captchaHandler);
    }

    @Override
    protected String getCariOnlineFullUrl() {
        return "http://www.vs.ch/cari-online/";
    }

    @Override
    protected HttpHost getCariHttpHost() {
        return httpHost;
    }

    @Override
    protected String getCariHttpHostname() {
        return "www.vs.ch";
    }
}
