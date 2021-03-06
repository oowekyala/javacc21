/* Copyright (c) 2008-2020 Jonathan Revusky, revusky@javacc.com
 * Copyright (c) 2006, Sun Microsystems Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *     * Redistributions of source code must retain the above copyright notices,
 *       this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name Jonathan Revusky, Sun Microsystems, Inc.
 *       nor the names of any contributors may be used to endorse or promote
 *       products derived from this software without specific prior written
 *       permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF
 * THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.javacc;

import java.io.*;
import java.util.*;

import com.javacc.lexgen.*;
import com.javacc.output.java.FilesGenerator;
import com.javacc.parsegen.*;
import com.javacc.parser.*;
import com.javacc.parser.tree.*;

import freemarker.template.TemplateException;

/**
 * This object is the root Node of the data structure that contains all the 
 * information regarding a JavaCC processing job.
 */
public class Grammar extends BaseNode {

    private String filename,
    parserClassName,
    lexerClassName,
    parserPackage,
    constantsClassName,
    baseNodeClassName="BaseNode";
    private CompilationUnit parserCode;
    private JavaCCOptions options = new JavaCCOptions(this);
    private String defaultLexicalState = "DEFAULT";
    private long nextGenerationIndex = 1L;
    private int lookaheadLimit;
    private boolean considerSemanticLA;
    private ParserData parserData;
    private LexerData lexerData = new LexerData(this);
    private int includeNesting;

    private List<TokenProduction> tokenProductions = new ArrayList<>();

    private Map<String, BNFProduction> productionTable;
    private Map<String, RegularExpression> namedTokensTable = new LinkedHashMap<>();
    private Map<String, String> tokenNamesToConstName = new HashMap<>();
    private List<JavaCCError> errors = new ArrayList<>();
    private Map<Integer, RegularExpression> regexpLookup = new HashMap<>();
    private Set<String> lexicalStates = new LinkedHashSet<>();
    private Map<Integer, String> tokenNames = new HashMap<>();
    private Set<String> nodeNames = new LinkedHashSet<>();
    private Map<String,String> nodeClassNames = new HashMap<>();
    private Map<String, String> nodePackageNames = new HashMap<>();
    private List<Node> codeInjections = new ArrayList<>();
    private boolean usesCommonTokenAction, usesTokenHook, usesCloseNodeScopeHook, usesOpenNodeScopeHook, usesjjtreeOpenNodeScope, usesjjtreeCloseNodeScope;

    private Set<RegexpStringLiteral> stringLiteralsToResolve = new HashSet<>();

    public Grammar(JavaCCOptions options) {
        this.options = options;
        options.setGrammar(this);
        parserData = new ParserData(this);
    }

    public String[] getLexicalStates() {
        return lexicalStates.toArray(new String[]{});
    }

    public void addStringLiteralToResolve(RegexpStringLiteral stringLiteral) {
        stringLiteralsToResolve.add(stringLiteral);
    }

    public Set<RegexpStringLiteral> getStringLiteralsToResolve() {
        return stringLiteralsToResolve;
    }

    Node parse(String location) throws IOException, ParseException {
        File file = new File(location);
        Reader input = new FileReader(file);
        JavaCCParser parser = new JavaCCParser(this, input);
        parser.setInputSource(file.getCanonicalFile().getName());
        setFilename(location);
        System.out.println("Parsing grammar file " + location + " . . .");
        GrammarFile rootNode = parser.Root();
        if (!isInInclude()) {
            addChild(rootNode);
        }
        return rootNode;
    }

    public Node include(String location) throws IOException, ParseException {
        File file = new File(location);
        if (!file.exists()) {
            if (!file.isAbsolute()) {
                file = new File(new File(this.filename).getParent(), location);
                if (file.exists()) {
                    location = file.getAbsolutePath();
                }
            }
        }
        if (location.toLowerCase().endsWith(".java") || location.endsWith(".jav")) {
            CompilationUnit cu = JavaCCParser.parseJavaFile(new FileReader(location), location);
            codeInjections.add(cu);
            return cu;
        } else {
            String prevLocation = this.filename;
            String prevDefaultLexicalState = this.defaultLexicalState;
            includeNesting++;
            Node root = parse(location);
            includeNesting--;
            setFilename(prevLocation);
            this.defaultLexicalState = prevDefaultLexicalState;
            return root;
        }
    }

    public void createOutputDir() {
        String outputDirectory = options.getOutputDirectory();
        if (outputDirectory.equals("")) {
            outputDirectory = ".";
        }
        File outputDir = new File(outputDirectory);
        if (!outputDir.exists()) {
            addWarning(null, "Output directory \"" + outputDir + "\" does not exist. Creating the directory.");

            if (!outputDir.mkdirs()) {

                addSemanticError(null, "Cannot create the output directory : " + outputDir);
                return;
            }
        }

        else if (!outputDir.isDirectory()) {
            addSemanticError(null, "\"" + outputDir + " is not a valid output directory.");
        }

        else if (!outputDir.canWrite()) {
            addSemanticError(null, "Cannot write to the output output directory : \"" + outputDir + "\"");
        }
    }

    /**
     * The name of the grammar file being processed.
     */
    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public void generateLexer() {
        lexerData.start();
    }

    public void semanticize() throws MetaParseException {
        for (String lexicalState : lexicalStates) {
            lexerData.addLexicalState(lexicalState);
        }
        //        semanticizer.start();
        parserData.semanticize();
    }

    public void buildParserInfo() throws MetaParseException {
        parserData.buildData();
    }

    public void generateFiles() throws ParseException, IOException, TemplateException {
        new FilesGenerator(this, codeInjections).generateAll();
    }

    public LexerData getLexerData() {
        return lexerData;
    }

    public ParserData getParserData() {
        return parserData;
    }

    public JavaCCOptions getOptions() {
        return options;
    }

    void setOptions(JavaCCOptions options) {
        this.options = options;
        options.setGrammar(this);
    }

    public String getConstantsClassName() {
        if (constantsClassName == null || constantsClassName.length() == 0) {
            constantsClassName = getParserClassName();
            if (constantsClassName.toLowerCase().endsWith("parser")) {
                constantsClassName = constantsClassName.substring(0, constantsClassName.length() -6);
            }
            constantsClassName += "Constants";
        }
        return constantsClassName;
    }

    public String getParserClassName() {
        if (parserClassName == null || parserClassName.length() ==0) {
            String name = new File(filename).getName();
            int lastDot = name.lastIndexOf('.');
            if (lastDot >0) {
                name = name.substring(0, lastDot);
            }
            if (!name.toLowerCase().endsWith("parser")) {
                name += "Parser";
            }
            if (Character.isLowerCase(name.charAt(0))) {
                name = name.substring(0, 1).toUpperCase() + name.substring(1);
            }
            parserClassName = name;
        }
        return parserClassName;
    }

    public void setParserClassName(String parserClassName) {
        this.parserClassName = parserClassName;
    }

    public void setLexerClassName(String lexerClassName) {
        this.lexerClassName = lexerClassName;
    }

    public void setConstantsClassName(String constantsClassName) {
        this.constantsClassName = constantsClassName;
    }

    public void setBaseNodeClassName(String baseNodeClassName) {
        this.baseNodeClassName = baseNodeClassName;
    }

    public String getBaseNodeClassName() {
        return baseNodeClassName;
    }

    public String getLexerClassName() {
        if (lexerClassName == null || lexerClassName.length() == 0) {
            lexerClassName = getParserClassName();
            if (lexerClassName.toLowerCase().endsWith("parser")) {
                lexerClassName = lexerClassName.substring(0, lexerClassName.length() - 6);
            }
            lexerClassName += "Lexer";
        }
        return lexerClassName;
    }

    public String getDefaultLexicalState() {
        return this.defaultLexicalState;
    }

    public void setDefaultLexicalState(String defaultLexicalState) {
        this.defaultLexicalState = defaultLexicalState;
        addLexicalState(defaultLexicalState);
    }

    public List<Node> getOtherParserCodeDeclarations() {
        List<Node> result = new ArrayList<Node>();
        if (parserCode != null) {
            for (int i = 0; i < parserCode.getChildCount(); i++) {
                Node child = parserCode.getChild(i);
                if (child instanceof Token) {
                    continue;
                }
                if (child instanceof PackageDeclaration || child instanceof ImportDeclaration) {
                    continue;
                }
                if (child instanceof TypeDeclaration) {
                    if (((TypeDeclaration) child).getName().equals(parserClassName)) {
                        continue;
                    }
                }
                result.add(child);
            }
        }
        return result;
    }


    public List<ImportDeclaration> getParserCodeImports() {
        List<ImportDeclaration> result = new ArrayList<ImportDeclaration>();
        if (parserCode != null) {
            result.addAll(parserCode.childrenOfType(ImportDeclaration.class));
        }
        return result;
    }

    public void setParserCode(CompilationUnit parserCode) {
        this.parserCode = parserCode;
        parserPackage = options.getParserPackage();
        String specifiedPackageName = parserCode.getPackageName();
        if (specifiedPackageName != null && specifiedPackageName.length() >0) {
            if (!parserPackage.equals("")) {
                if (!parserPackage.equals(specifiedPackageName)) {
                    String msg = "PARSER_PACKAGE was specified in the options directory as " + parserPackage + " but is specified in the PARSER_BEGIN/PARSER_END section as " + specifiedPackageName +".";
                    addSemanticError(null, msg);
                }
            }
            parserPackage = specifiedPackageName;
        }
        addCodeInjection(parserCode);
    }

    public void setParserPackage(String parserPackage) {
        this.parserPackage = parserPackage;
    }

    public Collection<BNFProduction> getParserProductions() {
        return getProductionTable().values();
    }

    /**
     * A symbol table of all grammar productions.
     */
    public Map<String, BNFProduction> getProductionTable() {
        if (productionTable == null) {
            productionTable = new LinkedHashMap<>();
            for (BNFProduction production : descendantsOfType(BNFProduction.class )) {
                productionTable.put(production.getName(), production);
            }
        }
        return productionTable;
    }

    public BNFProduction getProductionByName(String name) {
        return getProductionTable().get(name);
    }

    /**
     * Add a new lexical state
     */
    public void addLexicalState(String name) {
        lexicalStates.add(name);
    }

    /**
     * The list of all TokenProductions from the input file. This list includes
     * implicit TokenProductions that are created for uses of regular
     * expressions within BNF productions.
     */
    public List<TokenProduction> getAllTokenProductions() {
        return tokenProductions;
    }

    //    public List<TokenProduction> getAllTokenProductions() {
    //    	return descendantsOfType(TokenProduction.class);
    //    }

    public void addTokenProduction(TokenProduction tp) {
        tokenProductions.add(tp);
    }

    public RegularExpression getRegexpForToken(int index) {
        return regexpLookup.get(index);
    }

    public void addRegularExpression(int index, RegularExpression regexp) {
        regexpLookup.put(index, regexp);
    }

    /**
     * This is a symbol table that contains all named tokens (those that are
     * defined with a label). The index to the table is the image of the label
     * and the contents of the table are of type "RegularExpression".
     */
    public RegularExpression getNamedToken(String name) {
        return namedTokensTable.get(name);
    }

    public RegularExpression addNamedToken(String name, RegularExpression regexp) {
        if (!namedTokensTable.containsKey(name)) {
            namedTokensTable.put(name, regexp);
        }
        return null;
    }

    /**
     * Contains the same entries as "named_tokens_table", but this is an ordered
     * list which is ordered by the order of appearance in the input file.
     * (Actually, the only place where this is used is in generating the
     * XXXConstants.java file)
     */
    public List<RegularExpression> getOrderedNamedTokens() {
        List<RegularExpression> result = new ArrayList<RegularExpression>();
        result.addAll(namedTokensTable.values());
        return result;
    }

    /**
     * A mapping of ordinal values (represented as objects of type "Integer") to
     * the corresponding labels (of type "String"). An entry exists for an
     * ordinal value only if there is a labeled token corresponding to this
     * entry. If there are multiple labels representing the same ordinal value,
     * then only one label is stored.
     */

    public String getTokenName(int index) {
        String tokenName = tokenNames.get(index);
        return tokenName == null ? String.valueOf(index) : tokenName;
    }

    public String classNameFromTokenName(String tokenName) {
        if (Character.isDigit(tokenName.charAt(0))) {
            return null;
        }
        if (namedTokensTable.get(tokenName).isPrivate()) {
            return null;
        }
        tokenNamesToConstName.put(tokenName, tokenName);
        return tokenName;
    }

    public String constNameFromClassName(String className) {
        return this.tokenNamesToConstName.get(className);
    }

    public void addTokenName(int index, String name) {
        tokenNames.put(index, name);
    }

    public int getErrorCount() {
        int result = 0;
        for (JavaCCError error : errors) {
            if (error.type != JavaCCError.Type.WARNING)
                ++result;
        }
        return result;
    }

    public int getWarningCount() {
        int result = 0;
        for (JavaCCError error : errors) {
            if (error.type == JavaCCError.Type.WARNING)
                ++result;
        }
        return result;
    }

    public int getParseErrorCount() {
        int result = 0;
        for (JavaCCError error : errors) {
            if (error.type == JavaCCError.Type.PARSE)
                ++result;
        }
        return result;
    }

    public int getSemanticErrorCount() {
        int result = 0;
        for (JavaCCError error : errors) {
            if (error.type == JavaCCError.Type.SEMANTIC)
                ++result;
        }
        return result;
    }

    public void addSemanticError(Object node, String message) {
        JavaCCError error = new JavaCCError(this, JavaCCError.Type.SEMANTIC, message, node);
        System.err.println(error);
        errors.add(error);
    }

    public void addParseError(Object node, String message) {
        JavaCCError error = new JavaCCError(this, JavaCCError.Type.PARSE, message, node);
        System.err.println(error);
        errors.add(error);
    }

    public void addWarning(Object node, String message) {
        JavaCCError error = new JavaCCError(this, JavaCCError.Type.WARNING, message, node);
        System.err.println(error);
        errors.add(error);
    }

    /**
     * To avoid right-recursive loops when calculating follow sets, we use a
     * generation number which indicates if this expansion was visited by
     * LookaheadWalk.genFollowSet in the same generation. New generations are
     * obtained by incrementing the counter below, and the current generation is
     * stored in the non-static variable below.
     */
    public long nextGenerationIndex() {
        return nextGenerationIndex++;
    }

    public int getLookaheadLimit() {
        return lookaheadLimit;
    }

    public void setLookaheadLimit(int lookaheadLimit) {
        this.lookaheadLimit = lookaheadLimit;
    }

    public boolean considerSemanticLA() {
        return considerSemanticLA;
    }

    public void setConsiderSemanticLA(boolean b) {
        considerSemanticLA = b;
    }

    public Set<String> getNodeNames() {
        return nodeNames;
    }

    public void addNodeType(String nodeName) {
        if (nodeName.equals("void")) {
            return;
        }
        nodeNames.add(nodeName);
        nodeClassNames.put(nodeName, options.getNodePrefix() + nodeName);
        nodePackageNames.put(nodeName, getNodePackage());
    }

    public String getNodeClassName(String nodeName) {
        String className = nodeClassNames.get(nodeName);
        if (className ==null) {
            return options.getNodePrefix() + nodeName;
        }
        return className;
    }

    public String getNodePackageName(String nodeName) {
        return nodePackageNames.get(nodeName);
    }

    // A bit kludgy

    private void checkForHooks(Node node, String className) {
        if (node == null && null instanceof Token) {
            return;
        } 
        else if (node instanceof TokenManagerDecls) {
            ClassOrInterfaceBody body = node.childrenOfType(ClassOrInterfaceBody.class).get(0);
            checkForHooks(body, getLexerClassName());
        }
        else if (node instanceof ParserCodeDecls) {
            List<CompilationUnit> cus = node.childrenOfType(CompilationUnit.class);
            if (!cus.isEmpty()) {
                checkForHooks(cus.get(0), getParserClassName());
            }
        }
        else if (node instanceof CodeInjection) {
            CodeInjection ci = (CodeInjection) node;
            if (ci.name.equals(getLexerClassName())) {
                checkForHooks(ci.body, lexerClassName);
            } 
            else if (ci.name.equals(getParserClassName())) {
                checkForHooks(ci.body, parserClassName);
            }
        }
        else if (node instanceof TypeDeclaration) {
            TypeDeclaration typeDecl = (TypeDeclaration) node;
            String typeName = typeDecl.getName(); 
            if (typeName.equals(getLexerClassName()) || typeName.endsWith("." +lexerClassName)) {
                for (Iterator<Node> it = Nodes.iterator(typeDecl); it.hasNext();) {
                    checkForHooks(it.next(), lexerClassName);
                }
            }
            else if (typeName.equals(getParserClassName()) || typeName.endsWith("." + parserClassName)) {
                for (Iterator<Node> it = Nodes.iterator(typeDecl); it.hasNext();) {
                    checkForHooks(it.next(), parserClassName);
                }
            }
        }
        else if (node instanceof ClassOrInterfaceBodyDeclaration) {
            ClassOrInterfaceBodyDeclaration decl = (ClassOrInterfaceBodyDeclaration) node;
            String sig = decl.getFullNameSignatureIfMethod();
            if (sig != null) {
                String start = new StringTokenizer(sig, "(\n ").nextToken();
                if (className.equals(lexerClassName)) {
                    if (start.equals("CommonTokenAction")) {
                        usesCommonTokenAction = true;
                    }
                    else if (start.equals("tokenHook")) {
                        usesTokenHook = true;
                    }
                }
                else if (className.equals(parserClassName)) {
                    if (start.equals("jjtreeOpenNodeScope")) {
                        usesjjtreeOpenNodeScope = true;
                    }
                    else if (start.equals("jjtreeCloseNodeScope")) {
                        usesjjtreeCloseNodeScope = true;
                    }
                    else if (start.equals("openNodeScopeHook")) {
                        usesOpenNodeScopeHook = true;
                    }
                    else if (start.equals("closeNodeScopeHook")) {
                        usesCloseNodeScopeHook = true;
                    }
                }
            }
        } else {
            for (Iterator<Node> it= Nodes.iterator(node);  it.hasNext();) {
                checkForHooks(it.next(), className);
            }
        }
    }

    public void addCodeInjection(Node n) {
        checkForHooks(n, null);
        codeInjections.add(n);
    }

    public boolean getUsesCommonTokenAction() {
        return usesCommonTokenAction;
    }

    public boolean getUsesTokenHook() {
        return usesTokenHook;
    }

    public boolean getUsesjjtreeOpenNodeScope() {
        return usesjjtreeOpenNodeScope;
    }

    public boolean getUsesjjtreeCloseNodeScope() {
        return usesjjtreeCloseNodeScope;
    }

    public boolean getUsesOpenNodeScopeHook() {
        return usesOpenNodeScopeHook;
    }

    public boolean getUsesCloseNodeScopeHook() {
        return usesCloseNodeScopeHook;
    }
    public boolean isInInclude() {
        return includeNesting >0;
    }

    public String getParserPackage() {
        return parserPackage;
    }

    String getNodePackageName() {
        String nodePackage = options.getNodePackage();
        if (nodePackage.equals("")) 
            nodePackage = getParserPackage();
        return nodePackage;
    }

    public File getParserOutputDirectory() throws IOException {
        String baseSrcDir = options.getBaseSourceDirectory();
        if (baseSrcDir.equals("")) {
            String outputDirectory = options.getOutputDirectory();
            if (outputDirectory.equals("")) {
                return new File(filename).getParentFile();
            }
            return new File(outputDirectory);
        }
        File dir = new File(baseSrcDir);
        if (!dir.isAbsolute()){
            File inputFileDir = new File(filename).getParentFile();
            dir = new File(inputFileDir, baseSrcDir);
        }
        if (!dir.exists()) {
            if (!dir.mkdir())
                throw new FileNotFoundException("Directory " + dir.getAbsolutePath() + " does not exist.");
        }
        String packageName = getParserPackage();
        if (packageName != null  && packageName.length() >0) {
            packageName = packageName.replace('.', '/');
            dir = new File(dir, packageName);
            if (!dir.exists()) {
                dir.mkdir();
            }
        }
        return dir;
    }

    public File getNodeOutputDirectory(String nodeName) throws IOException {
        String nodePackage = getNodePackageName(nodeName);
        if (nodePackage == null) {
            nodePackage = options.getNodePackage();
        }
        String baseSrcDir = options.getBaseSourceDirectory();
        if (nodePackage == null || nodePackage.equals("") || baseSrcDir.equals("")) {
            return getParserOutputDirectory();
        }
        File baseSource = new File(baseSrcDir);
        if (!baseSource.isAbsolute()) {
            File grammarFileDir = new File(filename).getAbsoluteFile().getParentFile();
            baseSource = new File(grammarFileDir, baseSrcDir).getAbsoluteFile();
        }
        if (!baseSource.isDirectory()) {
            if (!baseSource.exists()) {
                throw new FileNotFoundException("Directory " + baseSrcDir + " does not exist.");
            }
            throw new FileNotFoundException(baseSrcDir + " is not a directory.");
        }
        File result = new File(baseSource, nodePackage.replace('.', '/')).getAbsoluteFile();
        if (!result.exists()) {
            if (!result.mkdirs()) {
                throw new IOException("Could not create directory " + result);
            }
        } else if (!result.isDirectory()) {
            throw new IOException(result.toString() + " is not a directory.");
        }
        return result;
    }

    public String getNodePackage() {
        String nodePackage = options.getNodePackage();
        if (nodePackage.equals("")) {
            nodePackage = this.getParserPackage();
        }
        return nodePackage;
    }

    public BitSet newBitSetForTokens() {
        return new BitSet(getLexerData().getTokenCount());
    }

    public String getCurrentNodeVariableName() {
        if (nodeVariableNameStack.isEmpty())
            return "null";
        return nodeVariableNameStack.get(nodeVariableNameStack.size() - 1);
    }

    private final Utils utils = new Utils();
    private List<String> nodeVariableNameStack = new ArrayList<>();

    public Utils getUtils() {return utils;}
    public class Utils {
        public void pushNodeVariableName(String jjtThis) {
            nodeVariableNameStack.add(jjtThis);
        }

        public void popNodeVariableName() {
            nodeVariableNameStack.remove(nodeVariableNameStack.size() - 1);
        }
    }
}
