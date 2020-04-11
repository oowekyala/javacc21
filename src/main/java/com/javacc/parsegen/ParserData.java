/* Copyright (c) 2008-2020jj Jonathan Revusky, revusky@javacc.com
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

package com.javacc.parsegen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.javacc.Grammar;
import com.javacc.MetaParseException;
import com.javacc.lexgen.LexerData;
import com.javacc.lexgen.LexicalState;
import com.javacc.lexgen.RegularExpression;
import com.javacc.parser.Node;
import com.javacc.parser.ParseException;
import com.javacc.parser.tree.BNFProduction;
import com.javacc.parser.tree.ExpansionChoice;
import com.javacc.parser.tree.ExpansionSequence;
import com.javacc.parser.tree.ExplicitLookahead;
import com.javacc.parser.tree.NonTerminal;
import com.javacc.parser.tree.OneOrMore;
import com.javacc.parser.tree.OneOrMoreRegexp;
import com.javacc.parser.tree.RegexpChoice;
import com.javacc.parser.tree.RegexpRef;
import com.javacc.parser.tree.RegexpSequence;
import com.javacc.parser.tree.RegexpSpec;
import com.javacc.parser.tree.RegexpStringLiteral;
import com.javacc.parser.tree.RepetitionRange;
import com.javacc.parser.tree.TokenProduction;
import com.javacc.parser.tree.TryBlock;
import com.javacc.parser.tree.ZeroOrMore;
import com.javacc.parser.tree.ZeroOrMoreRegexp;
import com.javacc.parser.tree.ZeroOrOne;
import com.javacc.parser.tree.ZeroOrOneRegexp;

/**
 * This class holds the remains of all the most icky legacy code that is used to build up the data
 * structure for the parser. The near-term (or possibly mid-term) goal is to refactor and clean it 
 * all up (JR).
 */
public class ParserData {

    private Grammar grammar;

    private LexerData lexerData;
    private int gensymindex;

    private List<MatchInfo> sizeLimitedMatches;

    /**
     * These lists are used to maintain the lists of lookaheads and expansions 
     * for which code generation in phase 2 and phase 3 is required. 
     */
    private List<Lookahead> phase2lookaheads = new ArrayList<>();

    private List<Expansion> phase3list = new ArrayList<>();

    public ParserData(Grammar grammar) {
        this.grammar = grammar;
        this.lexerData = grammar.getLexerData();
    }

    public void buildData() throws MetaParseException {
        for (BNFProduction production : grammar.getParserProductions()) {
            new Phase2TableBuilder().visit(production.getExpansion());
        }
        for (Lookahead lookahead : phase2lookaheads) {
            Expansion expansion= lookahead.getNestedExpansion();
            phase3list.add(expansion);
            expansion.setPhase3LookaheadAmount(lookahead.getAmount());
        }
        for (int phase3index=0; phase3index < phase3list.size(); phase3index++) {
            Expansion exp = phase3list.get(phase3index);
            new Phase3TableBuilder(exp.getPhase3LookaheadAmount()).visit(exp);
        }
        // Not sure why it's necessary, but we need to get rid of duplicates
        this.phase3list = new ArrayList<>(new LinkedHashSet<>(phase3list));
    }


    public List<Lookahead> getPhase2Lookaheads() {
        return phase2lookaheads;
    }

    public List<Expansion> getPhase3Expansions() {
        return phase3list;
    }

    public class Phase2TableBuilder extends Node.Visitor {
        public void visit(ExpansionChoice choice) {
            List<Lookahead> lookaheads = new ArrayList<Lookahead>();
            List<ExpansionSequence> choices = choice.childrenOfType(ExpansionSequence.class);
            for (ExpansionSequence nestedSeq : choices) {
                visit(nestedSeq);
                //Lookahead lookahead = (Lookahead) nestedSeq.getChild(0);
                Lookahead lookahead = nestedSeq.getLookahead();
                if (lookahead.getAlwaysSucceeds()) break;
                lookaheads.add(lookahead);
            }
            for (Lookahead lookahead : lookaheads) {
                checkForPhase2Lookahead(lookahead);
            }
        }

        private void handleOneOrMoreEtc(Expansion exp) {
            ++gensymindex;
            exp.setLabel("label_" + gensymindex);
            visit(exp.getNestedExpansion());
            Lookahead lookahead = exp.getLookahead();
            if (!lookahead.getAlwaysSucceeds()) {
                checkForPhase2Lookahead(lookahead);
            }
        }

        private String removeNonJavaIdentifierPart(String s) {
            StringBuilder buf = new StringBuilder(s.length());
            for (char c : s.toCharArray()) {
                boolean addChar = buf.length() == 0 ? (Character.isJavaIdentifierStart(c)) : Character.isJavaIdentifierPart(c);
                if (addChar) {
                    buf.append(c);
                }
                if (c == '.') buf.append((char) '_');
            }
            return buf.toString();
        }

        private void checkForPhase2Lookahead(Lookahead lookahead) {
            if (lookahead.getRequiresPhase2Routine()) {
                phase2lookaheads.add(lookahead);
                Expansion exp = lookahead.getNestedExpansion();
                String phase2name = "phase2_"  + (gensymindex++) +  "_" + removeNonJavaIdentifierPart(exp.getInputSource()) + "_line_" + exp.getBeginLine();
                exp.setPhase2RoutineName(phase2name);
                exp.setPhase3RoutineName(phase2name.replace("phase2",  "phase3"));
            }
        }

        public void visit(OneOrMore exp) {handleOneOrMoreEtc(exp);}

        public void visit(ZeroOrMore exp) {handleOneOrMoreEtc(exp);}

        public void visit(ZeroOrOne exp) {handleOneOrMoreEtc(exp);}

        public void visit(TryBlock exp) {visit(exp.getNestedExpansion());}

        public void visit(Lookahead la) {}
    };

    /**
     * A visitor that checks whether there is a self-referential loop in a
     * Regexp reference. It is a much more terse, readable replacement
     * for some ugly legacy code.
     * @author revusky
     *
     */
    public class RegexpVisitor extends Node.Visitor {

        private HashSet<RegularExpression> alreadyVisited = new HashSet<>(), currentlyVisiting = new HashSet<>();

        public void visit(RegexpRef ref) {
            RegularExpression referredTo = ref.getRegexp();
            if (!alreadyVisited.contains(referredTo)) {
                if (!currentlyVisiting.contains(referredTo)) {
                    currentlyVisiting.add(referredTo);
                    visit(referredTo);
                    currentlyVisiting.remove(referredTo);
                } else {
                    alreadyVisited.add(referredTo);
                    grammar.addSemanticError(ref, "Self-referential loop detected");
                }
            }
        }
    }



    public class Phase3TableBuilder extends Node.Visitor {
        private int lookaheadAmount;

        Phase3TableBuilder(int lookaheadAmount) {
            this.lookaheadAmount = lookaheadAmount;
        }

        public void visit(NonTerminal nt) {
            generate3R(nt.getProduction().getExpansion());
        }

        public void visit(ExpansionChoice choice) {
            for (Expansion sub: choice.getChoices()) {
                generate3R(sub);
            }
        }

        public void visit(ExpansionSequence sequence) {
            int prevLookaheadAmount = this.lookaheadAmount;
            for (Expansion sub: sequence.getUnits()) {
                visit(sub);
                lookaheadAmount -= sub.getMinimumSize();
                if (lookaheadAmount <=0) break;
            }
            this.lookaheadAmount = prevLookaheadAmount;
        }

        public void visit(RegularExpression re) {}
        public void visit(Lookahead la) {}

        public void visit(OneOrMore exp) {generate3R(exp.getNestedExpansion()); }
        public void visit(ZeroOrMore exp) {generate3R(exp.getNestedExpansion());}
        public void visit(ZeroOrOne exp) {generate3R(exp.getNestedExpansion());}


        private void generate3R(Expansion expansion) {
            // It appears that the only possible Expansion types here are ExpansionChoice and ExpansionSequence
            if (expansion.getPhase2RoutineName() == null) {
                gensymindex++;
                expansion.setPhase3RoutineName("phase3R_" + gensymindex);
            }
            if (expansion.getPhase3LookaheadAmount()< lookaheadAmount) {
                phase3list.add(expansion);
                expansion.setPhase3LookaheadAmount(lookaheadAmount);
            }
        }
    }

    // This method contains various sanity checks and adjustments
    // that have been in the code forever. There is a general need
    // to clean this up because it presents a significant obstacle
    // to progress, since the original code is written in such an opaque manner that it is
    // hard to understand what it does.
    public void semanticize() throws MetaParseException {

        if (grammar.getErrorCount() != 0)
            throw new MetaParseException();

        if (grammar.getOptions().getLookahead() > 1 && !grammar.getOptions().getForceLaCheck()) {
            grammar.addWarning(null,
                    "Lookahead adequacy checking not being performed since option LOOKAHEAD "
                            + "is more than 1.  Set option FORCE_LA_CHECK to true to force checking.");
        }


        /*
         * Check whether we have any LOOKAHEADs at non-choice points
         * REVISIT: Why is this not handled in the grammar spec?
         * The legacy code had some kind of very complex munging going on
         * in these cases, but serious analysis seems to show that it was not something
         * of any real value.
         */

        for (ExpansionSequence sequence : grammar.descendantsOfType(ExpansionSequence.class)) {
            Lookahead lookahead = sequence.getLookahead();
            Node parent = sequence.getParent();
            if (!(parent instanceof ExpansionChoice
                    || parent instanceof OneOrMore
                    || parent instanceof ZeroOrOne
                    || parent instanceof ZeroOrMore)
                    && lookahead instanceof ExplicitLookahead) {
                grammar.addSemanticError(lookahead, "Encountered LOOKAHEAD(...) at a non-choice location." );
            }
        }

        // Check that non-terminals have all been defined.
        for (NonTerminal nt : grammar.descendantsOfType(NonTerminal.class)) {
            if (nt.getProduction() == null) {
                grammar.addSemanticError(nt, "Non-terminal " + nt.getName() + " has not been defined.");
            }
        }


        /*
         * The following loop ensures that all target lexical states are
         * defined. Also piggybacking on this loop is the detection of <EOF> and
         * <name> in token productions. After reporting an error, these entries
         * are removed. Also checked are definitions on inline private regular
         * expressions. This loop works slightly differently when
         * USER_DEFINED_LEXER is set to true. In this case, <name> occurrences
         * are OK, while regular expression specs generate a warning.
         */
        for (TokenProduction tp: grammar.descendantsOfType(TokenProduction.class)) {
            for (RegexpSpec res : tp.getRegexpSpecs()) {
                if (res.getNextState() != null) {
                    if (lexerData.getLexicalStateIndex(res.getNextState()) == -1) {
                        grammar.addSemanticError(res.getNsTok(), "Lexical state \""
                                + res.getNextState() + "\" has not been defined.");
                    }
                }
                if (tp.isExplicit() && grammar.getOptions().getUserDefinedLexer()) {
                    grammar.addWarning(res.getRegexp(),
                            "Ignoring regular expression specification since "
                                    + "option USER_DEFINED_LEXER has been set to true.");
                } else if (tp.isExplicit()
                        && !grammar.getOptions().getUserDefinedLexer()
                        && res.getRegexp() instanceof RegexpRef) {
                    grammar
                    .addWarning(
                            res.getRegexp(),
                            "Ignoring free-standing regular expression reference.  "
                                    + "If you really want this, you must give it a different label as <NEWLABEL:<"
                                    + res.getRegexp().getLabel() + ">>.");
                    tp.removeChild(res);
                } else if (!tp.isExplicit() && res.getRegexp().isPrivate()) {
                    grammar.addSemanticError(res.getRegexp(),
                            "Private (#) regular expression cannot be defined within "
                                    + "grammar productions.");
                }
            }
        }

        /*
         * The following loop inserts all names of regular expressions into
         * "named_tokens_table" and "ordered_named_tokens". Duplications are
         * flagged as errors.
         */
        for (TokenProduction tp : grammar.descendantsOfType(TokenProduction.class)) {
            List<RegexpSpec> respecs = tp.getRegexpSpecs();
            for (RegexpSpec res : respecs) {
                RegularExpression re = res.getRegexp();
                if (!(re instanceof RegexpRef) && re.hasLabel()) {
                    String s = res.getRegexp().getLabel();
                    RegularExpression regexp = grammar.addNamedToken(s,
                            res.getRegexp());
                    if (regexp != null) {
                        grammar.addSemanticError(res.getRegexp(),
                                "Multiply defined lexical token name \"" + s
                                + "\".");
                    }
                    if (lexerData.getLexicalStateIndex(s) != -1) {
                        grammar.addSemanticError(res.getRegexp(),
                                "Lexical token name \"" + s
                                + "\" is the same as "
                                + "that of a lexical state.");
                    }
                }
            }
        }

        /*
         * The following code merges multiple uses of the same string in the
         * same lexical state and produces error messages when there are
         * multiple explicit occurrences (outside the BNF) of the string in the
         * same lexical state, or when within BNF occurrences of a string are
         * duplicates of those that occur as non-TOKEN's (SKIP, MORE,
         * SPECIAL_TOKEN) or private regular expressions. While doing this, this
         * code also numbers all regular expressions (by setting their ordinal
         * values), and populates the table "names_of_tokens".
         */
        //        	 for (TokenProduction tp: grammar.descendantsOfType(TokenProduction.class)) {
        // Cripes, for some reason this is order dependent!
        for (TokenProduction tp : grammar.getAllTokenProductions()) {
            List<RegexpSpec> respecs = tp.getRegexpSpecs();
            List<Map<String, Map<String, RegularExpression>>> table = new ArrayList<Map<String, Map<String, RegularExpression>>>();
            for (int i = 0; i < tp.getLexStates().length; i++) {
                LexicalState lexState = lexerData.getLexicalState(tp.getLexStates()[i]);
                table.add(lexState.getTokenTable());
            }
            for (RegexpSpec res : respecs) {
                if (res.getRegexp() instanceof RegexpStringLiteral) {
                    // TODO: Clean this mess up! (JR)
                    RegexpStringLiteral stringLiteral = (RegexpStringLiteral) res.getRegexp();
                    // This loop performs the checks and actions with respect to
                    // each lexical state.
                    for (int i = 0; i < table.size(); i++) {
                        // Get table of all case variants of "sl.image" into
                        // table2.
                        Map<String, RegularExpression> table2 = table.get(i).get(stringLiteral.getImage().toUpperCase());
                        if (table2 == null) {
                            // There are no case variants of "sl.image" earlier
                            // than the current one.
                            // So go ahead and insert this item.
                            if (stringLiteral.getOrdinal() == 0) {
                                stringLiteral.setOrdinal(lexerData.getTokenCount());
                                lexerData.addRegularExpression(stringLiteral);
                            }
                            table2 = new HashMap<String, RegularExpression>();
                            table2.put(stringLiteral.getImage(), stringLiteral);
                            table.get(i).put(stringLiteral.getImage().toUpperCase(), table2);
                        } else if (hasIgnoreCase(table2, stringLiteral.getImage())) { // hasIgnoreCase
                            // sets
                            // "other"
                            // if it
                            // is
                            // found.
                            // Since IGNORE_CASE version exists, current one is
                            // useless and bad.
                            if (!stringLiteral.tpContext.isExplicit()) {
                                // inline BNF string is used earlier with an
                                // IGNORE_CASE.
                                grammar
                                .addSemanticError(
                                        stringLiteral,
                                        "String \""
                                                + stringLiteral.getImage()
                                                + "\" can never be matched "
                                                + "due to presence of more general (IGNORE_CASE) regular expression "
                                                + "at line "
                                                + other.getBeginLine()
                                                + ", column "
                                                + other.getBeginColumn()
                                                + ".");
                            } else {
                                // give the standard error message.
                                grammar.addSemanticError(stringLiteral,
                                        "(1) Duplicate definition of string token \""
                                                + stringLiteral.getImage() + "\" "
                                                + "can never be matched.");
                            }
                        } else if (stringLiteral.tpContext.getIgnoreCase()) {
                            // This has to be explicit. A warning needs to be
                            // given with respect
                            // to all previous strings.
                            String pos = "";
                            int count = 0;
                            for (RegularExpression rexp : table2.values()) {
                                if (count != 0)
                                    pos += ",";
                                pos += " line " + rexp.getBeginLine();
                                count++;
                            }
                            if (count == 1) {
                                grammar.addWarning(stringLiteral,
                                        "String with IGNORE_CASE is partially superseded by string at"
                                                + pos + ".");
                            } else {
                                grammar.addWarning(stringLiteral,
                                        "String with IGNORE_CASE is partially superseded by strings at"
                                                + pos + ".");
                            }
                            // This entry is legitimate. So insert it.
                            if (stringLiteral.getOrdinal() == 0) {
                                stringLiteral.setOrdinal(lexerData.getTokenCount());
                                lexerData.addRegularExpression(stringLiteral);
                            }
                            table2.put(stringLiteral.getImage(), stringLiteral);
                            // The above "put" may override an existing entry
                            // (that is not IGNORE_CASE) and that's
                            // the desired behavior.
                        } else {
                            // The rest of the cases do not involve IGNORE_CASE.
                            RegularExpression re = (RegularExpression) table2.get(stringLiteral.getImage());
                            if (re == null) {
                                if (stringLiteral.getOrdinal() == 0) {
                                    stringLiteral.setOrdinal(lexerData.getTokenCount());
                                    lexerData.addRegularExpression(stringLiteral);
                                }
                                table2.put(stringLiteral.getImage(), stringLiteral);
                            } else if (tp.isExplicit()) {
                                // This is an error even if the first occurrence
                                // was implicit.
                                if (tp.getLexStates()[i].equals(grammar.getDefaultLexicalState())) {
                                    grammar.addSemanticError(stringLiteral,
                                            "(2) Duplicate definition of string token \""
                                                    + stringLiteral.getImage() + "\".");
                                } else {
                                    grammar.addSemanticError(stringLiteral,
                                            "(3) Duplicate definition of string token \""
                                                    + stringLiteral.getImage()
                                                    + "\" in lexical state \""
                                                    + tp.getLexStates()[i] + "\".");
                                }
                            } else if (!re.tpContext.getKind().equals("TOKEN")) {
                                grammar
                                .addSemanticError(
                                        stringLiteral,
                                        "String token \""
                                                + stringLiteral.getImage()
                                                + "\" has been defined as a \""
                                                + re.tpContext.getKind()
                                                + "\" token.");
                            } else if (re.isPrivate()) {
                                grammar
                                .addSemanticError(
                                        stringLiteral,
                                        "String token \""
                                                + stringLiteral.getImage()
                                                + "\" has been defined as a private regular expression.");
                            } else {
                                // This is now a legitimate reference to an
                                // existing RStringLiteral.
                                // So we assign it a number and take it out of
                                // "rexprlist".
                                // Therefore, if all is OK (no errors), then
                                // there will be only unequal
                                // string literals in each lexical state. Note
                                // that the only way
                                // this can be legal is if this is a string
                                // declared inline within the
                                // BNF. Hence, it belongs to only one lexical
                                // state - namely "DEFAULT".
                                stringLiteral.setOrdinal(re.getOrdinal());
                                tp.removeChild(res);
                            }
                        }
                    }
                } else if (!(res.getRegexp() instanceof RegexpRef)) {
                    res.getRegexp().setOrdinal(lexerData.getTokenCount());
                    lexerData.addRegularExpression(res.getRegexp());
                }
                if (!(res.getRegexp() instanceof RegexpRef)
                        && !res.getRegexp().getLabel().equals("")) {
                    grammar.addTokenName(res.getRegexp().getOrdinal(), res.getRegexp().getLabel());
                }
                if (!(res.getRegexp() instanceof RegexpRef)) {
                    grammar.addRegularExpression(res.getRegexp().getOrdinal(), res.getRegexp());
                }
            }
        }

        /*
         * The following code performs a tree walk on all regular expressions
         * attaching links to "RegexpRef"s. Error messages are given if
         * undeclared names are used, or if "RegexpRefs" refer to private
         * regular expressions or to regular expressions of any kind other than
         * TOKEN. In addition, this loop also removes top level "RJustName"s
         * from "rexprlist". This code is not executed if
         * grammar.getOptions().getUserDefinedLexer() is set to true. Instead
         * the following block of code is executed.
         */

        if (!grammar.getOptions().getUserDefinedLexer()) {
            List<RegexpRef> refs = grammar.descendantsOfType(RegexpRef.class);
            for (RegexpRef ref : refs) {
                String label = ref.getLabel();
                RegularExpression referenced = grammar.getNamedToken(label);
                if (referenced == null && !ref.getLabel().equals("EOF")) {
                    grammar.addSemanticError(ref,  "Undefined lexical token name \"" + label + "\".");
                } else if (ref.tpContext != null && !ref.tpContext.isExplicit()) {
                    if (referenced.isPrivate()) {
                        grammar.addSemanticError(ref, "Token name \"" + label + "\" refers to a private (with a #) regular expression.");
                    }   else if (!referenced.tpContext.getKind().equals("TOKEN")) {
                        grammar.addSemanticError(ref, "Token name \"" + label + "\" refers to a non-token (SKIP, MORE, IGNORE_IN_BNF) regular expression.");
                    }
                }
            }
            for (TokenProduction tp : grammar.descendantsOfType(TokenProduction.class)) {
                for (RegexpRef ref : tp.descendantsOfType(RegexpRef.class)) {
                    RegularExpression rexp = grammar.getNamedToken(ref.getLabel());
                    if (rexp != null) {
                        ref.setOrdinal(rexp.getOrdinal());
                        ref.setRegexp(rexp);
                    }
                }
            }
            for (TokenProduction tp : grammar.descendantsOfType(TokenProduction.class)) {
                List<RegexpSpec> respecs = tp.getRegexpSpecs();
                for (RegexpSpec res : respecs) {
                    if (res.getRegexp() instanceof RegexpRef) {
                        tp.removeChild(res);
                    }
                }
            }
        }

        /*
         * The following code is executed only if
         * grammar.getOptions().getUserDefinedLexer() is set to true. This code
         * visits all top-level "RJustName"s (ignores "RJustName"s nested within
         * regular expressions). Since regular expressions are optional in this
         * case, "RJustName"s without corresponding regular expressions are
         * given ordinal values here. If "RJustName"s refer to a named regular
         * expression, their ordinal values are set to reflect this. All but one
         * "RJustName" node is removed from the lists by the end of execution of
         * this code.
         */

        if (grammar.getOptions().getUserDefinedLexer()) {
            for (TokenProduction tp : grammar.getAllTokenProductions()) {
                List<RegexpSpec> respecs = tp.getRegexpSpecs();
                for (RegexpSpec res : respecs) {
                    if (res.getRegexp() instanceof RegexpRef) {

                        RegexpRef jn = (RegexpRef) res.getRegexp();
                        RegularExpression rexp = grammar
                                .getNamedToken(jn.getLabel());
                        if (rexp == null) {
                            jn.setOrdinal(lexerData.getTokenCount());
                            lexerData.addRegularExpression(jn);
                            grammar.addNamedToken(jn.getLabel(), jn);
                            grammar.addTokenName(jn.getOrdinal(),
                                    jn.getLabel());
                        } else {
                            jn.setOrdinal(rexp.getOrdinal());
                            tp.removeChild(res);
                        }
                    }
                }
            }
        }

        /*
         * The following code is executed only if
         * grammar.getOptions().getUserDefinedLexer() is set to true. This loop
         * labels any unlabeled regular expression and prints a warning that it
         * is doing so. These labels are added to "ordered_named_tokens" so that
         * they may be generated into the ...Constants file.
         */
        if (grammar.getOptions().getUserDefinedLexer()) {
            for (TokenProduction tp : grammar.getAllTokenProductions()) {
                List<RegexpSpec> respecs = tp.getRegexpSpecs();
                for (RegexpSpec res : respecs) {
                    if (grammar.getTokenName(res.getRegexp().getOrdinal()) == null) {
                        grammar.addWarning(res.getRegexp(),
                                "Unlabeled regular expression cannot be referred to by "
                                        + "user generated token manager.");
                    }
                }
            }
        }

        if (grammar.getErrorCount() != 0)
            throw new MetaParseException();

        if (grammar.getErrorCount() == 0) {

            for (Node child : grammar.descendants((n) -> n instanceof OneOrMore || n instanceof ZeroOrMore || n instanceof ZeroOrOne)) {
                Expansion exp = (Expansion) child;
                if (exp.getNestedExpansion().isPossiblyEmpty()) {
                    grammar.addSemanticError(exp, "Expansion can be matched by empty string.");
                }
            }


            if (!grammar.getOptions().getUserDefinedLexer()) {
                RegexpVisitor reVisitor = new RegexpVisitor();
                for (TokenProduction tp : grammar.getAllTokenProductions()) {
                    reVisitor.visit(tp);
                }
            }

            /*
             * The following code performs the lookahead ambiguity checking.
             */
            if (grammar.getErrorCount() == 0) {
                if (grammar.getOptions().getLookahead() ==1 || grammar.getOptions().getForceLaCheck()) {
                    for (ExpansionChoice choice : grammar.descendantsOfType(ExpansionChoice.class)) {
                        choiceCalc(choice);
                    }
                    for (Node node : grammar.descendants((n) -> n instanceof OneOrMore || n instanceof ZeroOrMore || n instanceof ZeroOrOne)) {
                        Expansion exp = (Expansion) node;
                        if (hasImplicitLookahead(exp.getNestedExpansion())) {
                            ebnfCalc(exp, exp.getNestedExpansion());
                        }
                    }
                }
            }

        }
        if (grammar.getErrorCount() != 0) {
            throw new MetaParseException();
        }
    }


    private RegularExpression other;

    // Checks to see if the "str" is superseded by another equal (except case)
    // string
    // in table.
    private boolean hasIgnoreCase(Map<String, RegularExpression> table,
            String str) {
        RegularExpression rexp;
        rexp = (RegularExpression) (table.get(str));
        if (rexp != null && !rexp.tpContext.getIgnoreCase()) {
            return false;
        }
        for (RegularExpression re : table.values()) {
            if (re.tpContext.getIgnoreCase()) {
                other = re;
                return true;
            }
        }
        return false;
    }

    private boolean hasImplicitLookahead(Expansion exp) {
        return !(exp instanceof ExpansionSequence) && !(exp.getLookahead() instanceof ExplicitLookahead);
    }

    private MatchInfo overlap(List<MatchInfo> matchList1, List<MatchInfo> matchList2) {
        for (MatchInfo match1 : matchList1) {
            for (MatchInfo match2 : matchList2) {
                int size = match1.firstFreeLoc;
                MatchInfo match3 = match1;
                if (size > match2.firstFreeLoc) {
                    size = match2.firstFreeLoc;
                    match3 = match2;
                }
                if (size != 0) {
                    // REVISIT. We don't have JAVACODE productions  any more!
                    // we wish to ignore empty expansions and the JAVACODE stuff
                    // here.
                    boolean diffFound = false;
                    for (int k = 0; k < size; k++) {
                        if (match1.match[k] != match2.match[k]) {
                            diffFound = true;
                            break;
                        }
                    }
                    if (!diffFound) {
                        return match3;
                    }
                }
            }
        }
        return null;
    }

    private String image(MatchInfo m) {
        String ret = "";
        for (int i = 0; i < m.firstFreeLoc; i++) {
            if (m.match[i] == 0) {
                ret += " <EOF>";
            } else {
                RegularExpression re = grammar.getRegexpForToken(m.match[i]);
                if (re instanceof RegexpStringLiteral) {
                    ret += " \"" + ParseException.addEscapes(((RegexpStringLiteral) re).getImage()) + "\"";
                } else if (re.getLabel() != null && !re.getLabel().equals("")) {
                    ret += " <" + re.getLabel() + ">";
                } else {
                    ret += " <token of kind " + i + ">";
                }
            }
        }
        if (m.firstFreeLoc == 0) {
            return "";
        } else {
            return ret.substring(1);
        }
    }

 	private void choiceCalc(ExpansionChoice ch) {
        int first = firstChoice(ch);
        // dbl[i] and dbr[i] are vectors of size limited matches for choice i
        // of ch. dbl ignores matches with semantic lookaheads (when
        // force_la_check
        // is false), while dbr ignores semantic lookahead.
        // List<MatchInfo>[] dbl = new List[ch.getChoices().size()];
        // List<MatchInfo>[] dbr = new List[ch.getChoices().size()];
        List<Expansion> choices = ch.getChoices();
        int numChoices = choices.size();
        List<List<MatchInfo>> dbl = new ArrayList<>(Collections.nCopies(numChoices, null));
        List<List<MatchInfo>> dbr = new ArrayList<>(Collections.nCopies(numChoices, null));

        int[] minLA = new int[choices.size() - 1];
        MatchInfo[] overlapInfo = new MatchInfo[choices.size() - 1];
        int[] other = new int[choices.size() - 1];
        MatchInfo m;
        //        List<MatchInfo> partialMatches;
        boolean overlapDetected;
        for (int la = 1; la <= grammar.getOptions().getChoiceAmbiguityCheck(); la++) {
            grammar.setLookaheadLimit(la);
            grammar.setConsiderSemanticLA(!grammar.getOptions().getForceLaCheck());
            for (int i = first; i < choices.size() - 1; i++) {
                initSizeLimitedMatches(choices, dbl, i);
            }
            grammar.setConsiderSemanticLA(false);
            for (int i = first + 1; i < choices.size(); i++) {
                initSizeLimitedMatches(choices, dbr, i);
            }
            if (la == 1) {
                for (int i = first; i < choices.size() - 1; i++) {
                    Expansion exp = choices.get(i);
                    if (exp.isPossiblyEmpty()) {
                        grammar
                                .addWarning(
                                        exp,
                                        "This choice can expand to the empty token sequence "
                                                + "and will therefore always be taken in favor of the choices appearing later.");
                        break;
                    }
                }
            }
            overlapDetected = false;
            for (int i = first; i < choices.size() - 1; i++) {
                for (int j = i + 1; j < choices.size(); j++) {
                    if ((m = overlap(dbl.get(i), dbr.get(j))) != null) {
                        minLA[i] = la + 1;
                        overlapInfo[i] = m;
                        other[i] = j;
                        overlapDetected = true;
                        break;
                    }
                }
            }
            if (!overlapDetected) {
                break;
            }
        }
        for (int i = first; i < choices.size() - 1; i++) {
            if (explicitLookahead(choices.get(i)) && !grammar.getOptions().getForceLaCheck()) {
                continue;
            }
            if (minLA[i] > grammar.getOptions().getChoiceAmbiguityCheck()) {
                grammar.addWarning(null, "Choice conflict involving two expansions at");
                System.err.print("         line " + (choices.get(i)).getBeginLine());
                System.err.print(", column " + (choices.get(i)).getBeginColumn());
                System.err.print(" and line " + (choices.get(other[i])).getBeginLine());
                System.err.print(", column " + (choices.get(other[i])).getBeginColumn());
                System.err.println(" respectively.");
                System.err
                        .println("         A common prefix is: " + image(overlapInfo[i]));
                System.err.println("         Consider using a lookahead of " + minLA[i] + " or more for earlier expansion.");
            } else if (minLA[i] > 1) {
                grammar.addWarning(null, "Choice conflict involving two expansions at");
                System.err.print("         line " + choices.get(i).getBeginLine());
                System.err.print(", column " + (choices.get(i)).getBeginColumn());
                System.err.print(" and line " + (choices.get(other[i])).getBeginLine());
                System.err.print(", column " + (choices.get(other[i])).getBeginColumn());
                System.err.println(" respectively.");
                System.err.println("         A common prefix is: " + image(overlapInfo[i]));
                System.err.println("         Consider using a lookahead of " + minLA[i] + " for earlier expansion.");
            }
        }
    }

    private void initSizeLimitedMatches(List<Expansion> choices, List<List<MatchInfo>> dbl, int i) {
        sizeLimitedMatches = new ArrayList<>();
        MatchInfo m = new MatchInfo(grammar.getLookaheadLimit());
        m.firstFreeLoc = 0;
        List<MatchInfo> partialMatches = new ArrayList<>();
        partialMatches.add(m);
        generateFirstSet(partialMatches, choices.get(i), sizeLimitedMatches, grammar.getLookaheadLimit(), stopOnSemLa);
        dbl.set(i, sizeLimitedMatches);
    }

    boolean explicitLookahead(Expansion exp) {
        if (!(exp instanceof ExpansionSequence)) {
            return false;
        }
        ExpansionSequence seq = (ExpansionSequence) exp;
        List<Expansion> es = seq.getUnits();
        if (es.isEmpty()) {
            //REVISIT: Look at this case carefully!
            return false;
        }
        return seq.getLookahead() instanceof ExplicitLookahead;
//Expansion e = seq.firstChildOfType(Expansion.class);
        //return e instanceof ExplicitLookahead;
    }

    int firstChoice(ExpansionChoice ch) {
        if (grammar.getOptions().getForceLaCheck()) {
            return 0;
        }
        List<Expansion> choices = ch.getChoices();
        for (int i = 0; i < choices.size(); i++) {
            if (!explicitLookahead(choices.get(i))) {
                return i;
            }
        }
        return choices.size();
    }

    private String image(Expansion exp) {
        if (exp instanceof OneOrMore) {
            return "(...)+";
        } else if (exp instanceof ZeroOrMore) {
            return "(...)*";
        } else /* if (exp instanceof ZeroOrOne) */ {
            return "[...]";
        }
    }

    void ebnfCalc(Expansion exp, Expansion nested) {
        // exp is one of OneOrMore, ZeroOrMore, ZeroOrOne
        MatchInfo m, m1 = null;
        List<MatchInfo> partialMatches = new ArrayList<>();
        int la;
        for (la = 1; la <= grammar.getOptions().getOtherAmbiguityCheck(); la++) {

            List<MatchInfo> first = generateFirstSet(nested, la, !grammar.getOptions().getForceLaCheck());
            List<MatchInfo> sizeLimitedMatches = new ArrayList<>();
            grammar.setConsiderSemanticLA(false);
            generateFollowSet(partialMatches, exp, grammar.nextGenerationIndex());

            List<MatchInfo> follow = sizeLimitedMatches;
            if ((m = overlap(first, follow)) == null) {
                break;
            }
            m1 = m;
        }
        if (la > grammar.getOptions().getOtherAmbiguityCheck()) {
            grammar.addWarning(exp, "Choice conflict in " + image(exp) + " construct " + "at line "
                    + exp.getBeginLine() + ", column " + exp.getBeginColumn() + ".");
            System.err
                    .println("         Expansion nested within construct and expansion following construct");
            System.err.println("         have common prefixes, one of which is: "
                    + image(m1));
            System.err.println("         Consider using a lookahead of " + la
                    + " or more for nested expansion.");
        } else if (la > 1) {
            grammar.addWarning(exp, "Choice conflict in " + image(exp) + " construct " + "at line "
                    + exp.getBeginLine() + ", column " + exp.getBeginColumn() + ".");
            System.err
                    .println("         Expansion nested within construct and expansion following construct");
            System.err.println("         have common prefixes, one of which is: " + image(m1));
            System.err.println("         Consider using a lookahead of " + la + " for nested expansion.");
        }
    }

    List<MatchInfo> generateFirstSet(Expansion exp, int limit, boolean considerSemLa) {
        List<MatchInfo> overflowedMatches = new ArrayList<>();
        MatchInfo m = new MatchInfo(limit);
        List<MatchInfo> partialMatches = new ArrayList<>();
        partialMatches.add(m);
        generateFirstSet(partialMatches, exp, overflowedMatches, grammar.getLookaheadLimit(), considerSemLa);
        return overflowedMatches;
    }

    /**
     * Produce a set of all possible prefix matches of the given expansion (with a match length
     * limit). For example for  {@code <A> (<B> | <C>)? <D>}, will produce {@code [ <A> ] }, {@code
     * [ <A> <B>, <A> <C> ]} {@code [ <A> <B> <D>, <A> <C> <D> ]}, depending on the length limit.
     * The return value is only useful in recursive calls, instead the overflowedMatches list is
     * filled with the matches that reached the max length.
     *
     * @param prefixes      List of previous matches that lead up to the expansion. For example in
     *                      {@code
     *                      <A> (<B> | <C>)? <D>}, when exploring the expansion for {@code <D>},
     *                      prefixes will contain the MatchInfos {@code <A> <B>} and {@code <A>
     *                      <C>}. Each time we encounter a terminal we grow each element of this
     *                      list by the given terminal.
     * @param limit         Max length to which the MatchInfos are allowed to grow, past this,
     *                      they'll be added to overflowedMatches. The algorithm terminates when all
     *                      prefixes have been added to that list, and this will be its result.
     * @param considerSemLa If true, semantic lookaheads act as a stop to how far the matches are
     *                      processed
     * @return The prefixes that could be completed with this expansion within the match limit.
     * These will be used as the prefixes to match the next expansion in a sequence.
     */
    private static List<MatchInfo> generateFirstSet(List<MatchInfo> prefixes,
                                                    Expansion exp,
                                                    List<MatchInfo> overflowedMatches,
                                                    int limit,
                                                    boolean considerSemLa) {

        if (exp instanceof RegularExpression) {

            List<MatchInfo> retval = new ArrayList<>();

            // Append this terminal to every partial match
            for (MatchInfo partialMatch : prefixes) {

                MatchInfo mnew = partialMatch.cons(exp, limit);

                if (mnew.matchLength() == limit) {
                    // Means the array of regex ids in mnew is full.
                    // Add to overflowedMatches so that the caller does not process it
                    overflowedMatches.add(mnew);
                } else {
                    retval.add(mnew);
                }
            }

            return retval;

        } else if (exp instanceof ExpansionChoice) {
            // union of FIRST of each branch

            List<MatchInfo> retval = new ArrayList<>();
            for (Expansion branch : ((ExpansionChoice) exp).getChoices()) {
                List<MatchInfo> branchPrefixes = generateFirstSet(prefixes, branch, overflowedMatches, limit, considerSemLa);
                retval.addAll(branchPrefixes);
            }
            return retval;

        } else if (exp instanceof ExpansionSequence) {

            List<MatchInfo> v = prefixes;
            for (Expansion e : ((ExpansionSequence) exp).getUnits()) {

                // Use the partial matches of each step as input to the next.
                v = generateFirstSet(v, e, overflowedMatches, limit, considerSemLa);
                if (v.size() == 0) // means overflowedMatches is full
                    break;
            }
            return v;

        } else if (exp instanceof OneOrMore) {

            List<MatchInfo> retval = new ArrayList<>();
            List<MatchInfo> v = prefixes;
            do {
                v = generateFirstSet(v, exp.getNestedExpansion(), overflowedMatches, limit, considerSemLa);
                if (v.isEmpty())
                    break;
                retval.addAll(v);
            } while (true);

            return retval;

        } else if (exp instanceof ZeroOrMore) {

            List<MatchInfo> retval = new ArrayList<>();
            retval.addAll(prefixes);
            List<MatchInfo> v = prefixes;
            while (true) {
                v = generateFirstSet(v, exp.getNestedExpansion(), overflowedMatches, limit, considerSemLa);
                if (v.size() == 0)
                    break;
                retval.addAll(v);
            }
            return retval;

        } else if (exp instanceof ZeroOrOne) {

            List<MatchInfo> retval = new ArrayList<>();
            retval.addAll(prefixes);
            retval.addAll(generateFirstSet(prefixes, exp.getNestedExpansion(), overflowedMatches, limit, considerSemLa));

            return retval;

        } else if (exp instanceof NonTerminal) {
            // simple recursion

            BNFProduction prod = ((NonTerminal) exp).getProduction();
            return generateFirstSet(prefixes, prod.getExpansion(), overflowedMatches, limit, considerSemLa);

        } else if (exp instanceof TryBlock) {
            // simple recursion

            return generateFirstSet(prefixes, exp.getNestedExpansion(), overflowedMatches, limit, considerSemLa);

        } else if (considerSemLa
                && exp instanceof Lookahead
                && ((Lookahead) exp).getSemanticLookahead() != null) {

            return Collections.emptyList();

        } else {

            return prefixes;

        }
    }

    public static <U> void listSplit(List<U> toSplit, List<U> mask, List<U> partInMask, List<U> rest) {
        for (U u : toSplit) {
            if (mask.contains(u))
                partInMask.add(u);
            else
                rest.add(u);
        }
    }

    // TODO: Clean up this crap, factor it out to use a visitor pattern as well
    List<MatchInfo> generateFollowSet(List<MatchInfo> partialMatches, Expansion exp, long generation) {
        if (exp.myGeneration == generation) {
            return new ArrayList<MatchInfo>();
        }
        // System.out.println("*** Parent: " + exp.parent);
        exp.myGeneration = generation;
        if (exp.getParent() == null) {
            List<MatchInfo> retval = new ArrayList<MatchInfo>();
            retval.addAll(partialMatches);
            return retval;
        } else if (exp.getParent() instanceof BNFProduction) {
            BNFProduction production = (BNFProduction) exp.getParent();
            List<MatchInfo> retval = new ArrayList<MatchInfo>();
            // System.out.println("1; gen: " + generation + "; exp: " + exp);
            for (NonTerminal nt : production.getReferringNonTerminals()) {
                List<MatchInfo> v = generateFollowSet(partialMatches, nt, generation);
                retval.addAll(v);
            }
            return retval;
        } else if (exp.getParent() instanceof ExpansionSequence) {
            ExpansionSequence seq = (ExpansionSequence) exp.getParent();
            List<MatchInfo> v = partialMatches;
            for (int i = exp.getIndex() + 1; i < seq.getChildCount(); i++) {
                v = generateFirstSet(v, (Expansion) seq.getChild(i), grammar.getLookaheadLimit());
                if (v.size() == 0)
                    return v;
            }
            List<MatchInfo> v1 = new ArrayList<MatchInfo>();
            List<MatchInfo> v2 = new ArrayList<MatchInfo>();
            listSplit(v, partialMatches, v1, v2);
            if (v1.size() != 0) {
                // System.out.println("2; gen: " + generation + "; exp: " +
                // exp);
                v1 = generateFollowSet(v1, seq, generation);
            }
            if (v2.size() != 0) {
                // System.out.println("3; gen: " + generation + "; exp: " +
                // exp);
                v2 = generateFollowSet(v2, seq, grammar.nextGenerationIndex());
            }
            v2.addAll(v1);
            return v2;
        } else if (exp.getParent() instanceof OneOrMore
                || exp.getParent() instanceof ZeroOrMore) {
            List<MatchInfo> moreMatches = new ArrayList<MatchInfo>();
            moreMatches.addAll(partialMatches);
            List<MatchInfo> v = partialMatches;
            while (true) {
                v = generateFirstSet(v, exp, grammar.getLookaheadLimit());
                if (v.size() == 0)
                    break;
                moreMatches.addAll(v);
            }
            List<MatchInfo> v1 = new ArrayList<MatchInfo>();
            List<MatchInfo> v2 = new ArrayList<MatchInfo>();
            listSplit(moreMatches, partialMatches, v1, v2);
            if (v1.size() != 0) {
                // System.out.println("4; gen: " + generation + "; exp: " +
                // exp);
                v1 = generateFollowSet(v1, (Expansion) exp.getParent(), generation);
            }
            if (v2.size() != 0) {
                // System.out.println("5; gen: " + generation + "; exp: " +
                // exp);
                v2 = generateFollowSet(v2, (Expansion) exp.getParent(), grammar.nextGenerationIndex());
            }
            v2.addAll(v1);
            return v2;
        } else {
            // System.out.println("6; gen: " + generation + "; exp: " + exp);
            return generateFollowSet(partialMatches, (Expansion) exp.getParent(), generation);
        }
    }

    /**
     * Represents a "match" of an expansion as a sequence of terminals. You can think of this as a
     * {@code List<RegularExpression>}, it's just implemented as an array of ints (I assume for
     * speed or whatever).
     */
    static final class MatchInfo {
        /**
         * Array of regex ids in sequence. Only items in [0,firstFreeLoc[ are set.
         */
        int[] match;
        int firstFreeLoc;

        MatchInfo(int lookaheadLimit) {
            this.match = new int[lookaheadLimit];
        }

        public int matchLength() {
            return firstFreeLoc;
        }

        private MatchInfo cons(Expansion exp, int lookaheadLimit) {
            MatchInfo mnew = new MatchInfo(lookaheadLimit);
            assert lookaheadLimit > firstFreeLoc;
            if (firstFreeLoc >= 0) {
                System.arraycopy(match, 0, mnew.match, 0, firstFreeLoc);
            }
            mnew.firstFreeLoc = firstFreeLoc;
            mnew.match[mnew.firstFreeLoc++] = exp.getOrdinal();
            return mnew;
        }
    }
}
