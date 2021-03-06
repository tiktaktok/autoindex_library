package com.mathieuclement.lib.autoindex.provider.common.captcha.event;

import com.mathieuclement.lib.autoindex.plate.Plate;
import com.mathieuclement.lib.autoindex.provider.exception.ProviderException;
import org.apache.http.HttpHost;
import org.apache.http.client.HttpClient;
import org.apache.http.protocol.HttpContext;

/**
 * Listener for classes that solve captcha.
 */
public interface CaptchaListener {
    void onCaptchaCodeRequested(Plate plate, String captchaImageUrl,
                                HttpClient httpClient, HttpHost httpHost, HttpContext httpContext,
                                String httpHostHeaderValue, AsyncAutoIndexProvider provider)
            throws ProviderException;

    void onCaptchaCodeAccepted(Plate plate);
}
