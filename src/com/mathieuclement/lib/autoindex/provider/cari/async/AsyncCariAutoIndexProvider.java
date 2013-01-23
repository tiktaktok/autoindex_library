package com.mathieuclement.lib.autoindex.provider.cari.async;

import com.mathieuclement.lib.autoindex.plate.Plate;
import com.mathieuclement.lib.autoindex.plate.PlateOwner;
import com.mathieuclement.lib.autoindex.plate.PlateOwnerDataException;
import com.mathieuclement.lib.autoindex.plate.PlateType;
import com.mathieuclement.lib.autoindex.provider.common.captcha.CaptchaException;
import com.mathieuclement.lib.autoindex.provider.common.captcha.event.AsyncAutoIndexProvider;
import com.mathieuclement.lib.autoindex.provider.exception.PlateOwnerHiddenException;
import com.mathieuclement.lib.autoindex.provider.exception.PlateOwnerNotFoundException;
import com.mathieuclement.lib.autoindex.provider.exception.ProviderException;
import com.mathieuclement.lib.autoindex.provider.exception.UnsupportedPlateException;
import com.mathieuclement.lib.autoindex.provider.utils.ResponseUtils;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.http.*;
import org.apache.http.client.CookieStore;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicHttpEntityEnclosingRequest;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.CoreProtocolPNames;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class AsyncCariAutoIndexProvider extends AsyncAutoIndexProvider {

    private Map<PlateType, Integer> plateTypeMapping = new LinkedHashMap<PlateType, Integer>();
    private String lookupOwnerPageName = "rechDet";

    public AsyncCariAutoIndexProvider() {
        super();
        initPlateTypeMapping();
    }

    private Set<Header> getHttpHeaders() {
        Set<Header> headers = new LinkedHashSet<Header>();

        headers.add(new BasicHeader("Content-Type", "application/x-www-form-urlencoded"));
        headers.add(new BasicHeader("Accept-Charset", "utf-8"));
        headers.add(new BasicHeader("Accept-Language", "fr"));
        headers.add(new BasicHeader("Accept-Encoding", "gzip,deflate,sdch"));
        headers.add(new BasicHeader("Connection", "keep-alive"));
        headers.add(new BasicHeader("User-Agent", "Swiss-AutoIndex/0.1"));
        headers.add(new BasicHeader("Referer", getCariOnlineFullUrl() + lookupOwnerPageName)); // spelling error on purpose as in the RFC
        headers.add(new BasicHeader("Origin", getCariHttpHost().getSchemeName() + "://" + getCariHttpHostname()));
        headers.add(getHostHeader());

        return headers;
    }

    private Header getHostHeader() {
        return new BasicHeader("Host", getCariHttpHostname());
    }

    private void initPlateTypeMapping() {
        plateTypeMapping.put(PlateType.AUTOMOBILE, 1);
        plateTypeMapping.put(PlateType.AUTOMOBILE_REPAIR_SHOP, 1);
        plateTypeMapping.put(PlateType.AUTOMOBILE_TEMPORARY, 1);
        plateTypeMapping.put(PlateType.AUTOMOBILE_BROWN, 6);

        plateTypeMapping.put(PlateType.MOTORCYCLE, 2);
        plateTypeMapping.put(PlateType.MOTORCYCLE_REPAIR_SHOP, 2);
        plateTypeMapping.put(PlateType.MOTORCYCLE_YELLOW, 3); // Jaune moto
        plateTypeMapping.put(PlateType.MOTORCYCLE_BROWN, 7); // Brun moto
        plateTypeMapping.put(PlateType.MOTORCYCLE_TEMPORARY, 2);

        plateTypeMapping.put(PlateType.MOPED, 20); // Cyclo

        plateTypeMapping.put(PlateType.AGRICULTURAL, 4);

        plateTypeMapping.put(PlateType.INDUSTRIAL, 5);

        plateTypeMapping.put(PlateType.BOAT, 21);
    }

    /**
     * Return the search page URL, e.g. "https://appls2.fr.ch/cari/"
     *
     * @return the search page URL
     */
    protected abstract String getCariOnlineFullUrl();

    protected void makeRequestBeforeCaptchaEntered(Plate plate) {
        HttpParams httpParams = new BasicHttpParams();
        httpParams.setParameter(CoreProtocolPNames.PROTOCOL_VERSION, HttpVersion.HTTP_1_1);
        makeRequestBeforeCaptchaEntered(plate, new DefaultHttpClient(httpParams));
    }

    protected void makeRequestBeforeCaptchaEntered(Plate plate, HttpClient httpClient) {

        // HTTP handling
        HttpContext httpContext = new BasicHttpContext();
        //httpParams.setParameter(ClientPNames.COOKIE_POLICY, CookiePolicy.RFC_2965);
        // use our own cookie store


        // Load page a first time and get that session cookie!
        HttpRequest dummyPageViewRequest = new BasicHttpRequest("GET", getCariOnlineFullUrl() + lookupOwnerPageName, HttpVersion.HTTP_1_1);
        CookieStore cookieStore = new BasicCookieStore();
        httpContext.setAttribute(ClientContext.COOKIE_STORE, cookieStore);
        //dummyPageViewRequest.setHeader(getHostHeader());
        for (Header header : getHttpHeaders()) {
            dummyPageViewRequest.addHeader(header);
        }
        try {
            HttpResponse dummyResponse = httpClient.execute(getCariHttpHost(), dummyPageViewRequest, httpContext);
            StatusLine statusLine = dummyResponse.getStatusLine();
            if (statusLine.getStatusCode() != 200) {
                firePlateRequestException(plate, new ProviderException("Bad status when doing the dummy page view request to get a session: " + statusLine.getStatusCode() + " " + statusLine.getReasonPhrase(), plate));
                return;
            }
            dummyResponse.getEntity().getContent().close();
        } catch (IOException e) {
            firePlateRequestException(plate, new ProviderException("Could not do the dummy page view request to get a session.", e, plate));
            return;
        }

        String captchaImageUrl = generateCaptchaImageUrl();
        fireCaptchaCodeRequested(plate, captchaImageUrl, httpClient, getCariHttpHost(), httpContext, getCariHttpHostname(), this);
    }

    @Override
    protected void doRequestAfterCaptchaEntered(String captchaCode, Plate plate, HttpClient httpClient, HttpContext httpContext) {
        // TODO Doesn't have Cari a TimeOut for the captcha or something like this? Connection can be closed after some time.
        // We have to get that time from the server. Then in the GUI, we show a count down, so the user can see how much time is left to enter the code.
        // After the time is over, up to a maximal number of times, the code is generated again and refreshed on the screen.

        // TODO Set referer
        // TODO Set User-Agent header to the most used browser (probably the last available version of Internet Explorer)
        BasicHttpEntityEnclosingRequest plateOwnerSearchRequest = new BasicHttpEntityEnclosingRequest("POST", getCariOnlineFullUrl() + lookupOwnerPageName, HttpVersion.HTTP_1_1);
        for (Header header : getHttpHeaders()) {
            plateOwnerSearchRequest.addHeader(header);
        }

        List<NameValuePair> postParams = new LinkedList<NameValuePair>();
        postParams.add(new BasicNameValuePair("no", String.valueOf(plate.getNumber())));

        if (!plateTypeMapping.containsKey(plate.getType())) {
            firePlateRequestException(plate, new UnsupportedPlateException("Plate type " + plate.getType() + " is not supported by the Cari provider yet.", plate));
            return;
        }
        postParams.add(new BasicNameValuePair("cat", String.valueOf(plateTypeMapping.get(plate.getType()))));

        // Set sous-catégorie to "Normale" (auto / moto / agricultural / industrial)
        int sousCat = 1;
        if (PlateType.BOAT.equals(plate.getType())) {
            sousCat = 11;
        } else if (PlateType.AUTOMOBILE_TEMPORARY.equals(plate.getType())) {
            sousCat = 2;
        } else if (PlateType.MOTORCYCLE_TEMPORARY.equals(plate.getType())) {
            sousCat = 2;
        } else if (PlateType.AUTOMOBILE_REPAIR_SHOP.equals(plate.getType())) {
            sousCat = 3;
        } else if (PlateType.MOTORCYCLE_REPAIR_SHOP.equals(plate.getType())) {
            sousCat = 3;
        } else if (PlateType.MOPED.equals(plate.getType())) {
            sousCat = 21;
        }
        postParams.add(new BasicNameValuePair("sousCat", String.valueOf(sousCat)));

        postParams.add(new BasicNameValuePair("captchaVal", captchaCode));

        // hidden parameters
        postParams.add(new BasicNameValuePair("action", "query"));
        postParams.add(new BasicNameValuePair("pageContext", "login"));
        postParams.add(new BasicNameValuePair("valider", "Continuer"));
        postParams.add(new BasicNameValuePair("effacer", "Effacer"));

        try {
            plateOwnerSearchRequest.setEntity(new UrlEncodedFormEntity(postParams));
        } catch (UnsupportedEncodingException e) {
            firePlateRequestException(plate, new ProviderException("Unsupported encoding for plate owner request parameters", e, plate));
            return;
        }

        try {
            HttpResponse plateOwnerResponse = httpClient.execute(getCariHttpHost(), plateOwnerSearchRequest, httpContext);
            if (plateOwnerResponse.getStatusLine().getStatusCode() != 200) {
                firePlateRequestException(plate, new ProviderException("Got status " + plateOwnerResponse.getStatusLine().getStatusCode()
                        + " from server when executing request to get plate owner of plate " + plate, plate));
                return;
            }

            // Extract the plate owner from the HTML response
            PlateOwner plateOwner = htmlToPlateOwner(plateOwnerResponse, plate);
            fireCaptchaCodeAccepted(plate);

            // Close connection and release resources
            httpClient.getConnectionManager().shutdown();

            firePlateOwnerFound(plate, plateOwner);
        } catch (IOException e) {
            firePlateRequestException(plate, new ProviderException("Could not perform plate owner request on plate " + plate, e, plate));
        } catch (PlateOwnerDataException e) {
            firePlateRequestException(plate, new ProviderException("Found a result for " + plate + " but there was a problem parsing that result.", e, plate));
        } catch (CaptchaException e) {
            firePlateRequestException(plate, new ProviderException("Problem with Captcha", e, plate));
        } catch (PlateOwnerNotFoundException e) {
            firePlateRequestException(plate, e);
        } catch (ProviderException e) {
            firePlateRequestException(plate, e);
        } catch (PlateOwnerHiddenException e) {
            firePlateRequestException(plate, e);
        }
    }

    protected abstract HttpHost getCariHttpHost();

    protected abstract String getCariHttpHostname();

    private static final Pattern plateOwnerPattern = Pattern.compile("<td class='libelle'>.+\\s*</td>\\s+<td( nowrap)?>\\s*(.+)\\s*</td>");

    private PlateOwner htmlToPlateOwner(HttpResponse response, Plate plate) throws IOException, PlateOwnerDataException, CaptchaException, ProviderException, PlateOwnerNotFoundException, PlateOwnerHiddenException {
        String htmlPage = ResponseUtils.toString(response);
        if (htmlPage.contains("Code incorrect")) {
            throw new CaptchaException("Invalid captcha code");
        }
        PlateOwner plateOwner = new PlateOwner();

        // In Fribourg, currently the message "Aucun détenteur trouvé!" is shown both when the owner wants to hide its data and the number is not allocated,
        // but in Valais, the pages are different. It prints "Ce numéro de plaque est hors tabelle" when nobody owns the number.
        if (htmlPage.contains("Aucun détenteur trouvé!") || htmlPage.contains("Ce numéro de plaque est hors tabelle") || htmlPage.contains("Plaque disponible")) {
            throw new PlateOwnerNotFoundException("Plate owner not found or hidden", plate);
        }

        // See http://www.vs.ch/navig/navig.asp?MenuID=25069&RefMenuID=0&RefServiceID=0
        if (htmlPage.contains("motivation") || htmlPage.contains("parent.parent.location.href=\"http://www.ocn.ch/ocn/fr/pub/ocn_online/autoindex/protection_des_donnees.htm\";")) {
            throw new PlateOwnerHiddenException("Plate owner doesn't want to publish his data.", plate);
        }

        // TODO Handle "Plaque réservée"
        if (htmlPage.contains("Plaque réservée")) {
            throw new PlateOwnerHiddenException("Reserved plate", plate);
        }

        // TODO I noticed in Valais, you can get the message "Plaque disponible". Maybe we can do something with that message.

        Matcher matcher = plateOwnerPattern.matcher(htmlPage);

        byte counter = 0;
        while (matcher.find()) {
            if (matcher.group(0).contains("checkField") || matcher.group(0).contains("Captcha Code generation error")) {
                throw new ProviderException("Something went bad because we were presented the form page again!", plate);
            }
            String data = matcher.group(2);
            data = ResponseUtils.removeUselessSpaces(data); // Clean data

            switch (counter) {
                case 3:
                    plateOwner.setName(unescapeHtml(data));
                    break;
                case 4:
                    plateOwner.setAddress(unescapeHtml(data));
                    break;
                case 5:
                case 6:
                    // We may either have Case 5 with "Complément d'adresse" and then Case 6 with Zip code + town
                    // or only Case 5 with Zip code + town
                    // This is why we have two case statements and there is a test below to see if zip is a number

                    // Separate Zip code from town name
                    String[] split = unescapeHtml(data).split(" ");
                    try {
                        plateOwner.setZip(Integer.parseInt(split[0])); // if this fails => we have a "Complément d'adresse" See second catch statement below.

                        try {
                            plateOwner.setTown(unescapeHtml(data).substring(split[0].length() + 1));
                        } catch (Exception e) {
                            plateOwner.setTown("[Error]");
                        }
                    } catch (Exception e) {
                        // Then case 5 is for the "Complément d'adresse".
                        plateOwner.setAddressComplement(data == null ? "" : data);
                    }
                    break;
            }

            counter++;
        }

        // Check plate owner data
        plateOwner.check();

        return plateOwner;
    }

    private String unescapeHtml(String escapedHtml) {
        return StringEscapeUtils.unescapeHtml4(escapedHtml);
    }

    public String generateCaptchaImageUrl() {
        return getCariOnlineFullUrl() + "drawCaptcha?rnd=" + Math.random();
    }

    private static Set<PlateType> supportedPlateTypes = new LinkedHashSet<PlateType>();

    static {
        supportedPlateTypes.add(PlateType.AUTOMOBILE);
        supportedPlateTypes.add(PlateType.AUTOMOBILE_BROWN);
        supportedPlateTypes.add(PlateType.AUTOMOBILE_TEMPORARY);
        supportedPlateTypes.add(PlateType.AUTOMOBILE_REPAIR_SHOP);

        supportedPlateTypes.add(PlateType.MOTORCYCLE);
        supportedPlateTypes.add(PlateType.MOTORCYCLE_YELLOW);
        supportedPlateTypes.add(PlateType.MOTORCYCLE_BROWN);
        supportedPlateTypes.add(PlateType.MOTORCYCLE_TEMPORARY);
        supportedPlateTypes.add(PlateType.MOTORCYCLE_REPAIR_SHOP);

        supportedPlateTypes.add(PlateType.MOPED);
        supportedPlateTypes.add(PlateType.AGRICULTURAL);
        supportedPlateTypes.add(PlateType.INDUSTRIAL);
        supportedPlateTypes.add(PlateType.BOAT);
    }

    @Override
    public boolean isPlateTypeSupported(PlateType plateType) {
        return supportedPlateTypes.contains(plateType);
    }
}