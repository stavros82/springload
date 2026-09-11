package com.springload.strategy;

import com.springload.dto.ExecutionSettings;
import com.springload.dto.ScenarioConfig;
import com.springload.dto.StressConfig;
import com.springload.dto.Thresholds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ingests Apache JMeter test plans (.jmx), traversing HTTPSamplerProxy elements
 * and translating them into ScenarioConfig entries for YAML execution.
 */
@Component
public class JmeterParserStrategy implements StressConfigParserStrategy {

    private static final Logger log = LoggerFactory.getLogger(JmeterParserStrategy.class);
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern PROPERTY = Pattern.compile("\\$\\{__P\\(([^,}]+)(?:,([^}]*))?\\)}");

    @Override
    public ParserType getType() {
        return ParserType.JMETER;
    }

    @Override
    public StressConfig parse(InputStream inputStream) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(inputStream);
            doc.getDocumentElement().normalize();

            String planName = doc.getDocumentElement().getAttribute("testname");
            if (planName == null || planName.isBlank()) {
                planName = "Imported JMeter Plan";
            }

            NodeList samplers = doc.getElementsByTagName("HTTPSamplerProxy");
            List<ScenarioConfig> scenarios = new ArrayList<>(samplers.getLength());

            String defaultDomain = extractConfigDefault(doc, "HTTPSampler.domain");
            String defaultPort   = extractConfigDefault(doc, "HTTPSampler.port");
            String defaultProtocol = extractConfigDefault(doc, "HTTPSampler.protocol");
            Map<String, String> variables = extractUserVariables(doc);

            for (int i = 0; i < samplers.getLength(); i++) {
                Element sampler = (Element) samplers.item(i);
                // absent enabled attribute means enabled (JMeter default)
                String enabled = sampler.getAttribute("enabled");
                if ("false".equals(enabled)) {
                    continue;
                }
                scenarios.add(buildScenario(sampler, doc, defaultDomain, defaultPort, defaultProtocol, variables));
            }

            log.info("Parsed JMeter plan '{}' with {} scenarios", planName, scenarios.size());

            String baseUrl = deriveBaseUrl(samplers, defaultDomain, defaultPort, defaultProtocol, variables);
            return new StressConfig(
                    planName,
                    baseUrl,
                    new ExecutionSettings(50, 30, 5),
                    scenarios,
                    new Thresholds(200, 500, 1.0)
            );
        } catch (Exception e) {
            log.error("Error parsing JMeter plan: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to parse JMeter plan: " + e.getMessage(), e);
        }
    }

    private Map<String, String> extractUserVariables(Document doc) {
            Map<String, String> variables = new LinkedHashMap<>();
            NodeList props = doc.getElementsByTagName("elementProp");
            for (int i = 0; i < props.getLength(); i++) {
                Element arguments = (Element) props.item(i);
                if (!"TestPlan.user_defined_variables".equals(arguments.getAttribute("name"))) {
                    continue;
                }
                NodeList entries = arguments.getElementsByTagName("elementProp");
                for (int j = 0; j < entries.getLength(); j++) {
                    Element entry = (Element) entries.item(j);
                    String name = stringProp(entry, "Argument.name", "");
                    String value = stringProp(entry, "Argument.value", "");
                    if (!name.isBlank()) {
                        variables.put(name, resolveProperties(value));
                    }
                }
            }
            return variables;
        }

    private String resolveProperties(String value) {
            Matcher property = PROPERTY.matcher(value);
            StringBuffer result = new StringBuffer();
            while (property.find()) {
                String replacement = property.group(2) == null ? "" : property.group(2);
                property.appendReplacement(result, Matcher.quoteReplacement(replacement));
            }
            property.appendTail(result);
            return result.toString();
        }

    private String resolveStaticVariables(String value, Map<String, String> variables) {
            if (value == null || value.isBlank()) return value;
            Matcher matcher = VARIABLE.matcher(value);
            StringBuffer result = new StringBuffer();
            while (matcher.find()) {
                String replacement = variables.getOrDefault(matcher.group(1), matcher.group());
                matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(result);
            return result.toString();
        }
    private String extractConfigDefault(Document doc, String propName) {
        NodeList configs = doc.getElementsByTagName("ConfigTestElement");
        for (int i = 0; i < configs.getLength(); i++) {
            String val = stringProp((Element) configs.item(i), propName, "");
            if (!val.isBlank()) return val;
        }
        return "";
    }

    private ScenarioConfig buildScenario(Element sampler, Document doc,
                                         String defaultDomain, String defaultPort, String defaultProtocol,
                                         Map<String, String> variables) {
        String name     = sampler.getAttribute("testname");
        String method   = stringProp(sampler, "HTTPSampler.method", "GET").toUpperCase();
        String protocol = stringProp(sampler, "HTTPSampler.protocol", defaultProtocol.isBlank() ? "http" : defaultProtocol);
        String domain   = resolveStaticVariables(stringProp(sampler, "HTTPSampler.domain", defaultDomain), variables);
        String port     = resolveStaticVariables(stringProp(sampler, "HTTPSampler.port", defaultPort), variables);
        String path     = resolveStaticVariables(stringProp(sampler, "HTTPSampler.path", "/"), variables);

        Map<String, String> queryParams = extractQueryParams(sampler);
        Map<String, String> headers     = extractHeaders(sampler, doc);
        String body = extractBody(sampler);
        Map<String, String> extractedVariables = extractPostProcessorVariables(sampler);

        return new ScenarioConfig(
                name.isBlank() ? method + " " + path : name,
                method,
                path.startsWith("/") ? path : "/" + path,
                1,
                headers,
                queryParams,
                body,
                true,
                true,
                extractedVariables
        );
    }

    /** Reads a {@code <stringProp>} or {@code <boolProp>} child value by name. */
    private String stringProp(Element parent, String propName, String defaultValue) {
        for (String tag : new String[]{"stringProp", "boolProp"}) {
            NodeList props = parent.getElementsByTagName(tag);
            for (int i = 0; i < props.getLength(); i++) {
                Element prop = (Element) props.item(i);
                if (propName.equals(prop.getAttribute("name"))) {
                    String text = prop.getTextContent();
                    return (text != null && !text.isBlank()) ? text.trim() : defaultValue;
                }
            }
        }
        return defaultValue;
    }

    private Map<String, String> extractQueryParams(Element sampler) {
        Map<String, String> params = new LinkedHashMap<>();
        boolean isBodyRaw = "true".equals(stringProp(sampler, "HTTPSampler.postBodyRaw", "false"));
        if (isBodyRaw) return params;
        NodeList elementProps = sampler.getElementsByTagName("elementProp");
        for (int i = 0; i < elementProps.getLength(); i++) {
            Element ep = (Element) elementProps.item(i);
            if (!"HTTPArgument".equals(ep.getAttribute("elementType"))) {
                continue;
            }
            String key = stringProp(ep, "Argument.name", "");
            String value = stringProp(ep, "Argument.value", "");
            if (!key.isBlank()) {
                params.put(key, value);
            }
        }
        return params;
    }

    /**
     * Looks for a HeaderManager sibling in the same hashTree as the sampler.
     * JMeter stores headers in a HeaderManager element adjacent to the sampler.
     */
    private Map<String, String> extractHeaders(Element sampler, Document doc) {
        Map<String, String> headers = new LinkedHashMap<>();
        NodeList managers = doc.getElementsByTagName("HeaderManager");
        for (int m = 0; m < managers.getLength(); m++) {
            Element manager = (Element) managers.item(m);
            NodeList headerProps = manager.getElementsByTagName("elementProp");
            for (int i = 0; i < headerProps.getLength(); i++) {
                Element ep = (Element) headerProps.item(i);
                String key = stringProp(ep, "Header.name", "");
                String value = stringProp(ep, "Header.value", "");
                if (!key.isBlank() && !isRestrictedHeader(key)) {
                    headers.put(key, value);
                }
            }
        }
        return headers;
    }

    private String extractBody(Element sampler) {
        // postBodyRaw=true: body is a single HTTPArgument with a blank name
        String postBodyRaw = stringProp(sampler, "HTTPSampler.postBodyRaw", "false");
        if (!"true".equals(postBodyRaw)) {
            return null;
        }
        NodeList elementProps = sampler.getElementsByTagName("elementProp");
        for (int i = 0; i < elementProps.getLength(); i++) {
            Element ep = (Element) elementProps.item(i);
            if (!"HTTPArgument".equals(ep.getAttribute("elementType"))) {
                continue;
            }
            // Body argument has a blank Argument.name — read value directly from child stringProp
            NodeList props = ep.getElementsByTagName("stringProp");
            for (int j = 0; j < props.getLength(); j++) {
                Element prop = (Element) props.item(j);
                if ("Argument.value".equals(prop.getAttribute("name"))) {
                    String text = prop.getTextContent();
                    if (text != null && !text.isBlank()) {
                        return text.trim();
                    }
                }
            }
        }
        return null;
    }

    /**
     * Extracts variable definitions from JSONPostProcessor and RegexExtractor
     * elements nested directly under this HTTPSamplerProxy.
     *
     * @param sampler The HTTPSamplerProxy element
     * @return Map of variable names to their extract expressions
     */
    private Map<String, String> extractPostProcessorVariables(Element sampler) {
        Map<String, String> extracted = new LinkedHashMap<>();

        // Look for JSONPostProcessor siblings in the parent hashTree
        extractJsonPostProcessorVariables(sampler, extracted);

        // Look for RegexExtractor siblings
        extractRegexExtractorVariables(sampler, extracted);

        return extracted;
    }

    /**
     * Extracts variables from JSONPostProcessor elements.
     * JSONPostProcessor has referenceNames (target vars) and jsonPathExprs (paths).
     */
    private void extractJsonPostProcessorVariables(Element sampler, Map<String, String> extracted) {
        NodeList allJsonProcessors = sampler.getOwnerDocument().getElementsByTagName("JSONPostProcessor");
        for (int i = 0; i < allJsonProcessors.getLength(); i++) {
            Element processor = (Element) allJsonProcessors.item(i);

            // Extract reference names (variable names)
            String refNames = stringProp(processor, "JSONPostProcessor.referenceNames", "");
            if (refNames.isBlank()) {
                continue;
            }

            // Extract JSONPath expressions
            String paths = stringProp(processor, "JSONPostProcessor.jsonPathExprs", "");
            if (paths.isBlank()) {
                continue;
            }

            // Split by semicolon and create mappings
            String[] names = refNames.split(";");
            String[] pathExprs = paths.split(";");

            for (int j = 0; j < names.length && j < pathExprs.length; j++) {
                String varName = names[j].trim();
                String pathExpr = pathExprs[j].trim();
                if (!varName.isEmpty() && !pathExpr.isEmpty()) {
                    extracted.put(varName, pathExpr);
                }
            }
        }
    }

    /**
     * Extracts variables from RegexExtractor elements.
     * RegexExtractor has refVal (variable), regex (pattern), and template (capture group).
     */
    private void extractRegexExtractorVariables(Element sampler, Map<String, String> extracted) {
        NodeList allRegexExtractors = sampler.getOwnerDocument().getElementsByTagName("RegexExtractor");
        for (int i = 0; i < allRegexExtractors.getLength(); i++) {
            Element extractor = (Element) allRegexExtractors.item(i);

            // Extract variable name
            String varName = stringProp(extractor, "RegexExtractor.refname", "");
            if (varName.isBlank()) {
                continue;
            }

            // Extract regex pattern
            String regex = stringProp(extractor, "RegexExtractor.regexp", "");
            if (regex.isBlank()) {
                continue;
            }

            // Extract template (capture group index, e.g., "$1$" for group 1)
            String template = stringProp(extractor, "RegexExtractor.template", "$1$");

            // Format as regex extractor rule: regex|template
            String extractorRule = regex + "|" + template;
            extracted.put(varName, extractorRule);
        }
    }

    private String buildBaseUrl(String protocol, String domain, String port) {
        if (domain.isBlank()) {
            return "http://localhost:8080";
        }
        String base = protocol + "://" + domain;
        if (!port.isBlank() && !port.equals("80") && !port.equals("443")) {
            base += ":" + port;
        }
        return base;
    }

    /** Derives the base URL from samplers or falls back to ConfigTestElement defaults. */
    private String deriveBaseUrl(NodeList samplers, String defaultDomain, String defaultPort,
                                 String defaultProtocol, Map<String, String> variables) {
        for (int i = 0; i < samplers.getLength(); i++) {
            Element sampler = (Element) samplers.item(i);
            if ("false".equals(sampler.getAttribute("enabled"))) continue;
            String domain = resolveStaticVariables(stringProp(sampler, "HTTPSampler.domain", ""), variables);
            if (!domain.isBlank()) {
                return buildBaseUrl(
                        stringProp(sampler, "HTTPSampler.protocol", "http"),
                        domain,
                        resolveStaticVariables(stringProp(sampler, "HTTPSampler.port", ""), variables)
                );
            }
        }
        if (!defaultDomain.isBlank()) {
            return buildBaseUrl(
                    defaultProtocol.isBlank() ? "http" : defaultProtocol,
                    resolveStaticVariables(defaultDomain, variables),
                    resolveStaticVariables(defaultPort, variables)
            );
        }
        return "http://localhost:8080";
    }

    private boolean isRestrictedHeader(String name) {
        String lower = name.trim().toLowerCase();
        return lower.equals("host") || lower.equals("content-length")
                || lower.equals("connection") || lower.equals("transfer-encoding");
    }
}
