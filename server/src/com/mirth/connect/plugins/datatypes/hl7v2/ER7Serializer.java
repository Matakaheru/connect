/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 * http://www.mirthcorp.com
 * 
 * The software in this package is published under the terms of the MPL
 * license a copy of which has been included with this distribution in
 * the LICENSE.txt file.
 */

package com.mirth.connect.plugins.datatypes.hl7v2;

import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLReaderFactory;

import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.DefaultXMLParser;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.parser.XMLParser;
import ca.uhn.hl7v2.util.Terser;
import ca.uhn.hl7v2.validation.impl.NoValidation;

import com.mirth.connect.donkey.model.message.SerializerException;
import com.mirth.connect.donkey.model.message.XmlSerializer;
import com.mirth.connect.model.converters.BatchAdaptor;
import com.mirth.connect.model.converters.BatchMessageProcessor;
import com.mirth.connect.model.converters.BatchMessageProcessorException;
import com.mirth.connect.model.converters.DocumentSerializer;
import com.mirth.connect.model.converters.IXMLSerializer;
import com.mirth.connect.model.converters.XMLPrettyPrinter;
import com.mirth.connect.model.datatype.SerializerProperties;
import com.mirth.connect.util.ErrorConstants;
import com.mirth.connect.util.ErrorMessageBuilder;
import com.mirth.connect.util.StringUtil;

public class ER7Serializer implements IXMLSerializer, BatchAdaptor {
    private Logger logger = Logger.getLogger(this.getClass());
    private PipeParser serializationPipeParser = null;
    private XMLParser serializationXmlParser = null;
    private PipeParser deserializationPipeParser = null;
    private XMLParser deserializationXmlParser = null;
    private String serializationSegmentDelimiter = null;
    private String deserializationSegmentDelimiter = null;
    private HL7v2SerializationProperties serializationProperties;
    private HL7v2DeserializationProperties deserializationProperties;
    
    public ER7Serializer(SerializerProperties properties) {
        serializationProperties = (HL7v2SerializationProperties) properties.getSerializationProperties();
        deserializationProperties = (HL7v2DeserializationProperties) properties.getDeserializationProperties();
        
        if (serializationProperties != null) {
            serializationSegmentDelimiter = StringUtil.unescape(serializationProperties.getSegmentDelimiter());
        }
        if (deserializationProperties != null) {
            deserializationSegmentDelimiter = StringUtil.unescape(deserializationProperties.getSegmentDelimiter());
        }
    }
    
    public String getDeserializationSegmentDelimiter() {
        return deserializationSegmentDelimiter;
    }

    /**
     * Do the serializer properties require serialization
     * @return
     */
    @Override
    public boolean isSerializationRequired(boolean toXml) {
    	boolean serializationRequired = false;
    	
    	if (toXml) {
    	    if (serializationProperties.isUseStrictParser() || serializationProperties.isUseStrictValidation() || !serializationProperties.isStripNamespaces() || serializationProperties.isHandleRepetitions() || serializationProperties.isHandleSubcomponents() || !serializationProperties.getSegmentDelimiter().equals("\\r\\n|\\r|\\n")) {
                serializationRequired = true;
            }
    	} else {
    	    if (deserializationProperties.isUseStrictParser() || deserializationProperties.isUseStrictValidation() || !deserializationProperties.getSegmentDelimiter().equals("\\r")) {
                serializationRequired = true;
            }
    	}
    	
    	return serializationRequired;
    }
    
    @Override
    public String transformWithoutSerializing(String message, XmlSerializer outboundSerializer) {
        ER7Serializer serializer = (ER7Serializer) outboundSerializer;
        
        String inputSegmentDelimiter = serializationSegmentDelimiter;
        String outputSegmentDelimiter = serializer.getDeserializationSegmentDelimiter();
        
        if (!inputSegmentDelimiter.equals(outputSegmentDelimiter)) {
            return message.replaceAll(inputSegmentDelimiter, outputSegmentDelimiter);
        }
        
        return message;
    }

    /**
     * Returns an XML-encoded HL7 message given an ER7-enconded HL7 message.
     * 
     * @param source
     *            an ER7-encoded HL7 message.
     * @return
     */
    @Override
    public String toXML(String source) throws SerializerException {
        try {
            if (serializationProperties.isUseStrictParser()) {
                if (serializationPipeParser == null || serializationXmlParser == null) {
                    serializationPipeParser = new PipeParser();
                    serializationXmlParser = new DefaultXMLParser();
            
                    // turn off strict validation if needed
                    if (!serializationProperties.isUseStrictValidation()) {
                        serializationPipeParser.setValidationContext(new NoValidation());
                        serializationXmlParser.setValidationContext(new NoValidation());
                    }
            
                    serializationXmlParser.setKeepAsOriginalNodes(new String[] { "NTE.3", "OBX.5" });
                }
                //TODO need to update how data type properties work after the beta. This may or may not be wrong.
                // Right now, if strict parser is used on the source, it will need to be used for the destinations as well.
                if (!serializationSegmentDelimiter.equals("\r")) {
                    source = source.replaceAll(serializationSegmentDelimiter, "\r");
                }
                return serializationXmlParser.encode(serializationPipeParser.parse(source.trim()));
            } else {
                ER7Reader er7Reader = new ER7Reader(serializationProperties.isHandleRepetitions(), serializationProperties.isHandleSubcomponents(), serializationSegmentDelimiter);
                StringWriter stringWriter = new StringWriter();
                XMLPrettyPrinter serializer = new XMLPrettyPrinter(stringWriter);
                serializer.setEncodeEntities(true);
                er7Reader.setContentHandler(serializer);
                er7Reader.parse(new InputSource(new StringReader(source)));
                return stringWriter.toString();
            }
        } catch (Exception e) {
            throw new SerializerException(e, ErrorMessageBuilder.buildErrorMessage(ErrorConstants.ERROR_500, "Error converting ER7 to XML", e));
        }
    }

    /**
     * Returns an ER7-encoded HL7 message given an XML-encoded HL7 message.
     * 
     * @param source
     *            a XML-encoded HL7 message.
     * @return
     */
    @Override
    public String fromXML(String source) throws SerializerException {
        try {
            if (deserializationProperties.isUseStrictParser()) {
                if (deserializationPipeParser == null || deserializationXmlParser == null) {
                    deserializationPipeParser = new PipeParser();
                    deserializationXmlParser = new DefaultXMLParser();
            
                    // turn off strict validation if needed
                    if (!deserializationProperties.isUseStrictValidation()) {
                        deserializationPipeParser.setValidationContext(new NoValidation());
                        deserializationXmlParser.setValidationContext(new NoValidation());
                    }
            
                    deserializationXmlParser.setKeepAsOriginalNodes(new String[] { "NTE.3", "OBX.5" });
                }
                
                return deserializationPipeParser.encode(deserializationXmlParser.parse(source));
            } else {
                /*
                 * The delimiters below need to come from the XML somehow. The
                 * ER7 handler should take care of it TODO: Ensure you get these
                 * elements from the XML
                 */

                String fieldSeparator = getNodeValue(source, "<MSH.1>", "</MSH.1>");

                if (StringUtils.isEmpty(fieldSeparator)) {
                    fieldSeparator = "|";
                }

                String componentSeparator = "^";
                String repetitionSeparator = "~";
                String subcomponentSeparator = "&";
                String escapeCharacter = "\\";

                /*
                 * Our delimiters usually look like this:
                 * <MSH.2>^~\&amp;</MSH.2> We need to decode XML entities
                 */
                String separators = getNodeValue(source, "<MSH.2>", "</MSH.2>").replaceAll("&amp;", "&");

                if (separators.length() == 4) {
                    // usually ^
                    componentSeparator = separators.substring(0, 1);
                    // usually ~
                    repetitionSeparator = separators.substring(1, 2);
                    // usually \
                    escapeCharacter = separators.substring(2, 3);
                    // usually &
                    subcomponentSeparator = separators.substring(3, 4);
                }

                XMLEncodedHL7Handler handler = new XMLEncodedHL7Handler(deserializationSegmentDelimiter, fieldSeparator, componentSeparator, repetitionSeparator, escapeCharacter, subcomponentSeparator, true);
                XMLReader reader = XMLReaderFactory.createXMLReader();
                reader.setContentHandler(handler);
                reader.setErrorHandler(handler);

                /*
                 * Parse, but first replace all spaces between brackets. This
                 * fixes pretty-printed XML we might receive.
                 */
                reader.parse(new InputSource(new StringReader(source.replaceAll("\\s*<([^/][^>]*)>", "<$1>").replaceAll("<(/[^>]*)>\\s*", "<$1>"))));
                return handler.getOutput().toString();
            }
        } catch (Exception e) {
            throw new SerializerException(e, ErrorMessageBuilder.buildErrorMessage(ErrorConstants.ERROR_500, "Error converting XML to ER7", e));
        }
    }

    @Override
    public Map<String, String> getMetadataFromDocument(Document document) {
        Map<String, String> metadata = new HashMap<String, String>();

        if (serializationProperties.isUseStrictParser()) {
            XMLParser xmlParser = new DefaultXMLParser();
            if (!serializationProperties.isUseStrictValidation()) {
                xmlParser.setValidationContext(new NoValidation());
            }
            
            xmlParser.setKeepAsOriginalNodes(new String[] { "NTE.3", "OBX.5" });
            
            try {
                DocumentSerializer serializer = new DocumentSerializer();
                String source = serializer.toXML(document);
                Message message = xmlParser.parse(source);
                Terser terser = new Terser(message);
                String sendingFacility = terser.get("/MSH-4-1");
                String event = terser.get("/MSH-9-1") + "-" + terser.get("/MSH-9-2");
                metadata.put("version", message.getVersion());
                metadata.put("type", event);
                metadata.put("source", sendingFacility);
                return metadata;
            } catch (Exception e) {
                logger.error(e.getMessage());
                return metadata;
            }
        } else {
            if (document.getElementsByTagName("MSH.4.1").getLength() > 0) {
                Node senderNode = document.getElementsByTagName("MSH.4.1").item(0);

                if ((senderNode != null) && (senderNode.getFirstChild() != null)) {
                    metadata.put("source", senderNode.getFirstChild().getTextContent());
                } else {
                    metadata.put("source", "");
                }
            }

            if (document.getElementsByTagName("MSH.9").getLength() > 0) {
                if (document.getElementsByTagName("MSH.9.1").getLength() > 0) {
                    Node typeNode = document.getElementsByTagName("MSH.9.1").item(0);

                    if (typeNode != null) {
                        String type = typeNode.getFirstChild().getNodeValue();

                        if (document.getElementsByTagName("MSH.9.2").getLength() > 0) {
                            Node subTypeNode = document.getElementsByTagName("MSH.9.2").item(0);
                            type += "-" + subTypeNode.getFirstChild().getTextContent();
                        }

                        metadata.put("type", type);
                    } else {
                        metadata.put("type", "Unknown");
                    }
                }
            }

            if (document.getElementsByTagName("MSH.12.1").getLength() > 0) {
                Node versionNode = document.getElementsByTagName("MSH.12.1").item(0);

                if (versionNode != null) {
                    metadata.put("version", versionNode.getFirstChild().getTextContent());
                } else {
                    metadata.put("version", "");
                }
            }

            return metadata;
        }
    }

    private String getNodeValue(String source, String startTag, String endTag) {
        int startIndex = -1;

        if ((startIndex = source.indexOf(startTag)) != -1) {
            return source.substring(startIndex + startTag.length(), source.indexOf(endTag, startIndex));
        } else {
            return "";
        }
    }

    @Override
    public void processBatch(Reader src, BatchMessageProcessor dest) throws Exception {
        // TODO: The values of these parameters should come from the protocol
        // properties passed to processBatch
        // TODO: src is a character stream, not a byte stream
        byte startOfMessage = (byte) 0x0B;
        byte endOfMessage = (byte) 0x1C;
        byte endOfRecord = (byte) 0x0D;

        Scanner scanner = null;
        try {
            scanner = new Scanner(src);
            scanner.useDelimiter(Pattern.compile(serializationSegmentDelimiter));
            StringBuilder message = new StringBuilder();
            char data[] = { (char) startOfMessage, (char) endOfMessage };
            boolean errored = false;
    
            while (scanner.hasNext()) {
                String line = scanner.next().replaceAll(new String(data, 0, 1), "").replaceAll(new String(data, 1, 1), "").trim();
    
                if ((line.length() == 0) || line.equals((char) endOfMessage) || line.startsWith("MSH")) {
                    if (message.length() > 0) {
                        try {
                            if (!dest.processBatchMessage(message.toString())) {
                                logger.warn("Batch processing stopped.");
                                return;
                            }
                        } catch (Exception e) {
                            errored = true;
                            logger.error("Error processing message in batch.", e);
                        }
    
                        message = new StringBuilder();
                    }
    
                    while ((line.length() == 0) && scanner.hasNext()) {
                        line = scanner.next();
                    }
    
                    if (line.length() > 0) {
                        message.append(line);
                        message.append((char) endOfRecord);
                    }
                } else if (line.startsWith("FHS") || line.startsWith("BHS") || line.startsWith("BTS") || line.startsWith("FTS")) {
                    // ignore batch headers
                } else {
                    message.append(line);
                    message.append((char) endOfRecord);
                }
            }
    
            /*
             * MIRTH-2058: Now that the file has been completely read, make sure to
             * process
             * anything remaining in the message buffer. There could have been lines
             * read in that were not closed with an EOM.
             */
            if (message.length() > 0) {
                try {
                    dest.processBatchMessage(message.toString());
                } catch (Exception e) {
                    errored = true;
                    logger.error("Error processing message in batch.", e);
                }
            }
    
            if (errored) {
                throw new BatchMessageProcessorException("Error processing message in batch.");
            }
        } finally {
            if (scanner != null) {
                scanner.close();
            }
        }
    }
}