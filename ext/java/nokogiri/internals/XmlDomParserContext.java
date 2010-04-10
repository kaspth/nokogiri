package nokogiri.internals;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import nokogiri.XmlDocument;
import nokogiri.XmlSyntaxError;

import org.apache.xerces.parsers.DOMParser;
import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyClass;
import org.jruby.exceptions.RaiseException;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.w3c.dom.Document;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 *
 * @author sergio
 */
public class XmlDomParserContext extends ParserContext {
    protected static final String FEATURE_LOAD_EXTERNAL_DTD =
        "http://apache.org/xml/features/nonvalidating/load-external-dtd";
    protected static final String FEATURE_INCLUDE_IGNORABLE_WHITESPACE =
        "http://apache.org/xml/features/dom/include-ignorable-whitespace";

    public static final long STRICT = 0;
    public static final long RECOVER = 1;
    public static final long NOENT = 2;
    public static final long DTDLOAD = 4;
    public static final long DTDATTR = 8;
    public static final long DTDVALID = 16;
    public static final long NOERROR = 32;
    public static final long NOWARNING = 64;
    public static final long PEDANTIC = 128;
    public static final long NOBLANKS = 256;
    public static final long SAX1 = 512;
    public static final long XINCLUDE = 1024;
    public static final long NONET = 2048;
    public static final long NODICT = 4096;
    public static final long NSCLEAN = 8192;
    public static final long NOCDATA = 16384;
    public static final long NOXINCNODE = 32768;

    protected DOMParser parser;

    protected boolean strict, recover, noEnt, dtdLoad, dtdAttr, dtdValid,
            noError, noWarning, pedantic, noBlanks, sax1, xInclude, noNet,
            noDict, nsClean, noCdata, noXIncNode;

    protected NokogiriErrorHandler errorHandler;

    public XmlDomParserContext(Ruby runtime, IRubyObject options) {
        this(runtime, options.convertToInteger().getLongValue());
    }

    public XmlDomParserContext(Ruby runtime, long options) {
        super(runtime);

        if(options == STRICT) {
            this.strict = true;
            this.recover = this.noEnt = this.dtdLoad = this.dtdAttr =
                    this.dtdValid = this.noError = this.noWarning =
                    this.pedantic = this.noBlanks = this.sax1 = this.xInclude =
                    this.noNet = this.noDict = this.nsClean = this.noCdata =
                    this.noXIncNode = false;
        } else {
            this.strict = false;
            this.recover = (options & RECOVER) == RECOVER;
            this.noEnt = (options & NOENT) == NOENT;
            this.dtdLoad = (options & DTDLOAD) == DTDLOAD;
            this.dtdAttr = (options & DTDATTR) == DTDATTR;
            this.dtdValid = (options & DTDVALID) == DTDVALID;
            this.noError = (options & NOERROR) == NOERROR;
            this.noWarning = (options & NOWARNING) == NOWARNING;
            this.pedantic = (options & PEDANTIC) == PEDANTIC;
            this.noBlanks = (options & NOBLANKS) == NOBLANKS;
            this.sax1 = (options & SAX1) == SAX1;
            this.xInclude = (options & XINCLUDE) == XINCLUDE;
            this.noNet = (options & NONET) == NONET;
            this.noDict = (options & NODICT) == NODICT;
            this.nsClean = (options & NSCLEAN) == NSCLEAN;
            this.noCdata = (options & NOCDATA) == NOCDATA;
            this.noXIncNode = (options & NOXINCNODE) == NOXINCNODE;
        }

        if(this.continuesOnError()){
            this.errorHandler = new NokogiriNonStrictErrorHandler();
        } else {
            this.errorHandler = new NokogiriStrictErrorHandler();
        }

        initParser();
    }

    protected void initParser() {
        parser = new XmlDomParser();

        parser.setErrorHandler(this.errorHandler);

        if (noBlanks()) {
            setFeature(FEATURE_INCLUDE_IGNORABLE_WHITESPACE, false);
        }
        // If we turn off loading of external DTDs complete, we don't
        // getthe publicID.  Instead of turning off completely, we use
        // an entity resolver that returns empty documents.
        if (dtdLoad()) {
            setFeature(FEATURE_LOAD_EXTERNAL_DTD, true);
        } else {
            parser.setEntityResolver(new EntityResolver() {
                    public InputSource resolveEntity(String arg0, String arg1)
                        throws SAXException, IOException {
                        ByteArrayInputStream empty =
                            new ByteArrayInputStream(new byte[0]);
                        return new InputSource(empty);
                    }
                });
        }
    }

    /**
     * Convenience method that catches and ignores SAXException
     * (unrecognized and unsupported exceptions).
     */
    protected void setFeature(String feature, boolean value) {
        try {
            parser.setFeature(feature, value);
        } catch (SAXException e) {
            // ignore
        }
    }

    /**
     * Convenience method that catches and ignores SAXException
     * (unrecognized and unsupported exceptions).
     */
    protected void setProperty(String property, Object value) {
        try {
            parser.setProperty(property, value);
        } catch (SAXException e) {
            // ignore
        }
    }

    public void addErrorsIfNecessary(ThreadContext context, XmlDocument doc) {
        Ruby ruby = context.getRuntime();
        RubyArray errors = ruby.newArray(this.errorHandler.getErrorsReadyForRuby(context));
        doc.setInstanceVariable("@errors", errors);
    }

    public XmlDocument getDocumentWithErrorsOrRaiseException(ThreadContext context, Exception ex) {
        if(this.continuesOnError()) {
            XmlDocument doc = this.getNewEmptyDocument(context);
            this.addErrorsIfNecessary(context, doc);
            ((RubyArray) doc.getInstanceVariable("@errors")).append(new XmlSyntaxError(context.getRuntime(), ex));
            return doc;
        } else {
            throw new RaiseException(new XmlSyntaxError(context.getRuntime(), ex));
        }
    }

    protected XmlDocument getNewEmptyDocument(ThreadContext context) {
        IRubyObject[] args = new IRubyObject[0];
        return (XmlDocument) XmlDocument.rbNew(context,
                    context.getRuntime().getClassFromPath("Nokogiri::XML::Document"),
                    args);
    }

    public boolean continuesOnError() {
        return this.recover;
    }

    /**
     * This method is broken out so that HtmlDomParserContext can
     * override it.
     */
    protected XmlDocument wrapDocument(ThreadContext context,
                                       RubyClass klass,
                                       Document doc) {
        return new XmlDocument(context.getRuntime(), klass, doc);
    }

    /**
     * Must call setInputSource() before this method.
     */
    public XmlDocument parse(ThreadContext context,
                             IRubyObject klass,
                             IRubyObject url) {
        Ruby ruby = context.getRuntime();

        try {
            Document doc = do_parse();
            XmlDocument xmlDoc = wrapDocument(context, (RubyClass)klass, doc);
            xmlDoc.setUrl(url);
            addErrorsIfNecessary(context, xmlDoc);
            return xmlDoc;
        } catch (SAXException e) {
            return getDocumentWithErrorsOrRaiseException(context, e);
        } catch (IOException e) {
            return getDocumentWithErrorsOrRaiseException(context, e);
        }
    }

    protected Document do_parse() throws SAXException, IOException {
        if (noBlanks() || getPublicId(getInputSource()) != null) {
            String adjustedContent = tweakPublicId(asString(getInputSource()));
            if (noBlanks()) {
                adjustedContent = adjustedContent.replaceAll("(>\\n)", ">")
                        .replaceAll("\\s{1,}<", "<")
                        .replaceAll(">\\s{1,}", ">");
            }
            StringReader sr = new StringReader((new String(adjustedContent)));
            parser.parse(new InputSource(sr));
        } else {
            parser.parse(getInputSource());
        }
        return parser.getDocument();
    }
    
    private String asString(InputSource input) throws IOException {
        Reader reader = input.getCharacterStream();
        /*
         * when this block is used, XmlSchema.from_document raises ClassCastException since
         * the given document is null.
        InputStream istream = input.getByteStream();
        if (reader == null) {
            if (istream != null) {
                reader = new InputStreamReader(istream);
            }
        }*/
        if (reader == null) return "";
        StringBuffer content = new StringBuffer();
        char[] cbuf = new char[2048];
        int length;
        while ((length = reader.read(cbuf)) != -1) {
            content.append(cbuf, 0, length);
        }
        //if (istream != null) istream.reset();
        reader.reset();
        return new String(content);
    }

    private static Pattern doctype = Pattern.compile("<!DOCTYPE(.)+PUBLIC(()|[^>]*)>");
    private static Pattern publicId = Pattern.compile("\"(-|/|\\d|:|-|_|\\.|[a-zA-Z]|,|\\s)+\"");

    private String getPublicId(InputSource input) throws IOException {
        Matcher mm = doctype.matcher(asString(input));
        while(mm.find()) {
            return mm.group();
        }
        return null;
    }
 
    private String tweakPublicId(String str) {
        String newStr = tweak(str);
        return newStr == null ? str : str.replace(str, tweak(str));
    }

    private String tweak(String str) {
        List<String> list = getIds(str);
        if (list.size() == 2) {
            return null;
        } else if (list.size() == 1) {
            return str.replace(list.get(0), list.get(0) + " \"\"");
        }
        return null;
    }
    
    private List<String> getIds(String str) {
        List<String> list = new ArrayList<String>();
        Matcher mm = publicId.matcher(str);
        while(mm.find()) {
            list.add(mm.group());
        }
        return list;
    }


    public boolean dtdAttr() { return this.dtdAttr; }

    public boolean dtdLoad() { return this.dtdLoad; }

    public boolean dtdValid() { return this.dtdValid; }

    public boolean noBlanks() { return this.noBlanks; }

    public boolean noCdata() { return this.noCdata; }

    public boolean noDict() { return this.noDict; }

    public boolean noEnt() { return this.noEnt; }

    public boolean noError() { return this.noError; }

    public boolean noNet() { return this.noNet; }

    public boolean noWarning() { return this.noWarning; }

    public boolean nsClean() { return this.nsClean; }

    public boolean pedantic() { return this.pedantic; }

    public boolean recover() { return this.recover; }

    public boolean sax1() { return this.sax1; }

    public boolean strict() { return this.strict; }

    public boolean xInclude() { return this.xInclude; }

}