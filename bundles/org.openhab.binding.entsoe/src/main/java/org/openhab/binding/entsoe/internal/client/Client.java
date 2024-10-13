/**
 * Copyright (c) 2010-2024 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.entsoe.internal.client;

import java.io.IOException;
import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.entsoe.internal.exception.EntsoeConfigurationException;
import org.openhab.binding.entsoe.internal.exception.EntsoeResponseException;
import org.openhab.core.io.net.http.HttpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * @author Miika Jukka - Initial contribution
 * @author Jørgen Melhus - Contribution
 */
@NonNullByDefault
public class Client {
    private final Logger logger = LoggerFactory.getLogger(Client.class);

    private DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();

    public Map<Instant, EntsoeTimeSerie> doGetRequest(Request request, int timeout, String configResolution)
            throws EntsoeResponseException, EntsoeConfigurationException {
        try {
            logger.debug("Sending GET request with parameters: {}", request);
            String url = request.toUrl();
            String responseText = HttpUtil.executeUrl("GET", url, timeout);
            if (responseText == null) {
                throw new EntsoeResponseException("Request failed");
            }
            logger.trace("Response: {}", responseText);
            return parseXmlResponse(responseText, configResolution);
        } catch (IOException e) {
            if (e.getMessage().contains("Authentication challenge without WWW-Authenticate header")) {
                throw new EntsoeConfigurationException("Authentication failed. Please check your security token");
            }
            throw new EntsoeResponseException(e);
        } catch (ParserConfigurationException | SAXException e) {
            throw new EntsoeResponseException(e);
        }
    }

    private Map<Instant, EntsoeTimeSerie> parseXmlResponse(String responseText, String configResolution)
            throws ParserConfigurationException, SAXException, IOException, EntsoeResponseException,
            EntsoeConfigurationException {
        Map<Instant, EntsoeTimeSerie> responseMap = new LinkedHashMap<>();

        DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
        Document document = documentBuilder.parse(new InputSource(new StringReader(responseText)));
        document.getDocumentElement().normalize();

        // Check for rejection
        if (document.getDocumentElement().getNodeName().equals("Acknowledgement_MarketDocument")) {
            NodeList reasonOfRejection = document.getElementsByTagName("Reason");
            Node reasonNode = reasonOfRejection.item(0);
            Element reasonElement = (Element) reasonNode;
            String reasonCode = reasonElement.getElementsByTagName("code").item(0).getTextContent();
            String reasonText = reasonElement.getElementsByTagName("text").item(0).getTextContent();
            throw new EntsoeResponseException(
                    String.format("Request failed with API response: Code %s, Text %s", reasonCode, reasonText));
        }

        // Get all "timeSeries" nodes from document
        NodeList listOfTimeSeries = document.getElementsByTagName("TimeSeries");
        boolean resolutionFound = false;
        for (int i = 0; i < listOfTimeSeries.getLength(); i++) {
            Node timeSeriesNode = listOfTimeSeries.item(i);
            if (timeSeriesNode.getNodeType() == Node.ELEMENT_NODE) {
                Element timeSeriesElement = (Element) timeSeriesNode;

                String currency = timeSeriesElement.getElementsByTagName("currency_Unit.name").item(0).getTextContent();
                String measureUnit = timeSeriesElement.getElementsByTagName("price_Measure_Unit.name").item(0)
                        .getTextContent();

                NodeList listOfPeriod = timeSeriesElement.getElementsByTagName("Period");
                Node periodNode = listOfPeriod.item(0);
                Element periodElement = (Element) periodNode;

                NodeList listOfTimeInterval = periodElement.getElementsByTagName("timeInterval");
                Node startTimeNode = listOfTimeInterval.item(0);
                Element startTimeElement = (Element) startTimeNode;
                String startTime = startTimeElement.getElementsByTagName("start").item(0).getTextContent();
                Instant startTimeInstant = ZonedDateTime.parse(startTime, DateTimeFormatter.ISO_ZONED_DATE_TIME)
                        .toInstant();
                Node endTimeNode = listOfTimeInterval.item(0);
                Element endTimeElement = (Element) endTimeNode;
                String endTime = endTimeElement.getElementsByTagName("end").item(0).getTextContent();
                Instant endTimeInstant = ZonedDateTime.parse(endTime, DateTimeFormatter.ISO_ZONED_DATE_TIME)
                        .toInstant();

                String resolution = periodElement.getElementsByTagName("resolution").item(0).getTextContent();
                Duration durationResolution = Duration.parse(resolution);
                Duration durationTotal = Duration.between(startTimeInstant, endTimeInstant);
                int numberOfDurations = Math.round(durationTotal.toMinutes() / durationResolution.toMinutes());

                logger.debug("\"timeSeries\" node: {}/{} with start time: {} and resolution {}", (i + 1),
                        listOfTimeSeries.getLength(), startTimeInstant.atZone(ZoneId.of("UTC")), resolution);

                NodeList listOfPoints = periodElement.getElementsByTagName("Point");

                /*
                 * EntsoE changed their API on October 1 2024 so that they use the A03 curve type instead of A01. The
                 * difference between these curve types is that in A03 they don’t repeat an hour if it has the same
                 * price
                 * as the previous hour.
                 */
                int pointNr = 0;
                for (int p = 0; p < numberOfDurations && resolution.equalsIgnoreCase(configResolution); p++) {
                    resolutionFound = true;
                    Node pointNode = listOfPoints.item(pointNr);

                    int multiplier = p;
                    if (pointNode != null) {
                        Element pointElement = (Element) pointNode;
                        String price = pointElement.getElementsByTagName("price.amount").item(0).getTextContent();
                        Double priceAsDouble = Double.parseDouble(price);
                        EntsoeTimeSerie t = new EntsoeTimeSerie(currency, measureUnit, priceAsDouble, startTimeInstant,
                                multiplier, resolution);
                        responseMap.put(t.getInstant(), t);
                        logger.trace("\"Point\" node: {}/{} with values: {} - {} {}/{}", (p + 1), numberOfDurations,
                                t.getUtcTime(), priceAsDouble, currency, measureUnit);
                    }

                    Node nextPointNode = listOfPoints.item(pointNr + 1);
                    if (nextPointNode != null) {
                        Element nextPointElement = (Element) nextPointNode;
                        Node nextPositionNode = nextPointElement.getElementsByTagName("position").item(0);
                        if (nextPositionNode != null) {
                            int nextPosition = Integer.parseInt(nextPositionNode.getTextContent());
                            if (nextPosition == p + 2) {
                                pointNr++;
                            }
                        }
                    }

                }
            }
        }
        if (!resolutionFound) {
            throw new EntsoeConfigurationException("Resolution " + configResolution + " not found in ENTSOE response");
        }
        return responseMap;
    }
}
