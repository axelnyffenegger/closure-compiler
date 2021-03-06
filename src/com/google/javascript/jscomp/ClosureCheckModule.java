/*
 * Copyright 2015 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.javascript.jscomp;

import static com.google.common.base.Ascii.isUpperCase;
import static com.google.common.base.Ascii.toLowerCase;
import static com.google.common.base.Ascii.toUpperCase;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Predicates;
import com.google.javascript.jscomp.NodeTraversal.AbstractModuleCallback;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Checks that goog.module() is used correctly.
 *
 * <p>Note that this file only does checks that can be done per-file. Whole program checks happen
 * during goog.module rewriting, in {@link ClosureRewriteModule}.
 */
public final class ClosureCheckModule extends AbstractModuleCallback
    implements HotSwapCompilerPass {
  static final DiagnosticType AT_EXPORT_IN_GOOG_MODULE =
      DiagnosticType.error(
          "JSC_AT_EXPORT_IN_GOOG_MODULE",
          "@export has no effect here");

  static final DiagnosticType AT_EXPORT_IN_NON_LEGACY_GOOG_MODULE =
      DiagnosticType.error(
          "JSC_AT_EXPORT_IN_GOOG_MODULE",
          "@export is not allowed here in a non-legacy goog.module."
          + " Consider using goog.exportSymbol instead.");

  static final DiagnosticType GOOG_MODULE_IN_NON_MODULE =
      DiagnosticType.error(
          "JSC_GOOG_MODULE_IN_NON_MODULE",
          "goog.module() call must be the first statement in a module.");

  static final DiagnosticType GOOG_MODULE_REFERENCES_THIS = DiagnosticType.error(
      "JSC_GOOG_MODULE_REFERENCES_THIS",
      "The body of a goog.module cannot reference 'this'.");

  static final DiagnosticType GOOG_MODULE_USES_THROW = DiagnosticType.error(
      "JSC_GOOG_MODULE_USES_THROW",
      "The body of a goog.module cannot use 'throw'.");

  static final DiagnosticType GOOG_MODULE_USES_GOOG_MODULE_GET = DiagnosticType.error(
      "JSC_GOOG_MODULE_USES_GOOG_MODULE_GET",
      "It's illegal to use a 'goog.module.get' at the module top-level."
      + " Did you mean to use goog.require instead?");

  static final DiagnosticType DUPLICATE_NAME_SHORT_REQUIRE =
      DiagnosticType.error(
          "JSC_DUPLICATE_NAME_SHORT_REQUIRE",
          "Found multiple goog.require statements importing identifier ''{0}''.");

  static final DiagnosticType INVALID_DESTRUCTURING_REQUIRE =
      DiagnosticType.error(
          "JSC_INVALID_DESTRUCTURING_REQUIRE",
          "Destructuring goog.require must be a simple object pattern.");

  static final DiagnosticType INVALID_DESTRUCTURING_FORWARD_DECLARE =
      DiagnosticType.error(
          "JSC_INVALID_DESTRUCTURING_FORWARD_DECLARE",
          "Cannot destructure a forward-declared type");

  static final DiagnosticType LET_GOOG_REQUIRE =
      DiagnosticType.disabled(
          "JSC_LET_GOOG_REQUIRE",
          "Module imports must be constant. Please use 'const' instead of 'let'.");

  static final DiagnosticType MULTIPLE_MODULES_IN_FILE =
      DiagnosticType.error(
          "JSC_MULTIPLE_MODULES_IN_FILE",
          "There should only be a single goog.module() statement per file.");

  static final DiagnosticType MODULE_AND_PROVIDES =
      DiagnosticType.error(
          "JSC_MODULE_AND_PROVIDES",
          "A file using goog.module() may not also use goog.provide() statements.");

  static final DiagnosticType ONE_REQUIRE_PER_DECLARATION =
      DiagnosticType.error(
          "JSC_ONE_REQUIRE_PER_DECLARATION",
          "There may only be one goog.require() per var/let/const declaration.");

  static final DiagnosticType INCORRECT_SHORTNAME_CAPITALIZATION =
      DiagnosticType.disabled(
          "JSC_INCORRECT_SHORTNAME_CAPITALIZATION",
          "The capitalization of short name {0} is incorrect; it should be {1}.");

  static final DiagnosticType EXPORT_NOT_A_MODULE_LEVEL_STATEMENT =
      DiagnosticType.error(
          "JSC_EXPORT_NOT_A_MODULE_LEVEL_STATEMENT",
          "Exports must be a statement at the top-level of a module");

  static final DiagnosticType EXPORT_REPEATED_ERROR =
      DiagnosticType.error(
          "JSC_EXPORT_REPEATED_ERROR",
          "Name cannot be exported multiple times. Previous export on line {0}.");

  static final DiagnosticType REFERENCE_TO_MODULE_GLOBAL_NAME =
      DiagnosticType.error(
          "JSC_REFERENCE_TO_MODULE_GLOBAL_NAME",
          "References to the global name of a module are not allowed. Perhaps you meant exports?");

  static final DiagnosticType REFERENCE_TO_FULLY_QUALIFIED_IMPORT_NAME =
      DiagnosticType.disabled(
          "JSC_REFERENCE_TO_FULLY_QUALIFIED_IMPORT_NAME",
          "Reference to fully qualified import name ''{0}''."
              + " Imports in goog.module should use the return value of goog.require instead.");

  public static final DiagnosticType REFERENCE_TO_SHORT_IMPORT_BY_LONG_NAME_INCLUDING_SHORT_NAME =
      DiagnosticType.disabled(
          "JSC_REFERENCE_TO_SHORT_IMPORT_BY_LONG_NAME_INCLUDING_SHORT_NAME",
          "Reference to fully qualified import name ''{0}''."
              + " Please use the short name ''{1}'' instead.");

  static final DiagnosticType JSDOC_REFERENCE_TO_FULLY_QUALIFIED_IMPORT_NAME =
      DiagnosticType.disabled(
          "JSC_JSDOC_REFERENCE_TO_FULLY_QUALIFIED_IMPORT_NAME",
          "Reference to fully qualified import name ''{0}'' in JSDoc."
              + " Imports in goog.module should use the return value of goog.require instead.");

  public static final DiagnosticType
      JSDOC_REFERENCE_TO_SHORT_IMPORT_BY_LONG_NAME_INCLUDING_SHORT_NAME =
          DiagnosticType.disabled(
              "JSC_JSDOC_REFERENCE_TO_SHORT_IMPORT_BY_LONG_NAME_INCLUDING_SHORT_NAME",
              "Reference to fully qualified import name ''{0}'' in JSDoc."
                  + " Please use the short name ''{1}'' instead.");

  static final DiagnosticType REQUIRE_NOT_AT_TOP_LEVEL =
      DiagnosticType.error(
          "JSC_REQUIRE_NOT_AT_TOP_LEVEL",
          "goog.require() must be called at file scope.");

  private final AbstractCompiler compiler;

  private static class ModuleInfo {
    // Name of the module in question (i.e. the argument to goog.module)
    private final String name;
    // Mapping from fully qualified goog.required names to the import LHS node
    private final Map<String, Node> importsByLongRequiredName = new HashMap<>();
    // Module-local short names for goog.required symbols.
    private final Set<String> shortImportNames = new HashSet<>();
    // The node of the default (non-named) export of this module, if it exists.
    private Node defaultExportNode = null;

    ModuleInfo(String moduleName) {
      this.name = moduleName;
    }
  }

  private ModuleInfo currentModule = null;

  public ClosureCheckModule(AbstractCompiler compiler) {
    this.compiler = compiler;
  }

  @Override
  public void process(Node externs, Node root) {
    NodeTraversal.traverseEs6(compiler, root, this);
  }

  @Override
  public void hotSwapScript(Node scriptRoot, Node originalRoot) {
    NodeTraversal.traverseEs6(compiler, scriptRoot, this);
  }

  @Override
  public void enterModule(NodeTraversal t, Node scopeRoot) {
    Node firstStatement = scopeRoot.getFirstChild();
    if (NodeUtil.isExprCall(firstStatement)) {
      Node call = firstStatement.getFirstChild();
      Node callee = call.getFirstChild();
      if (callee.matchesQualifiedName("goog.module")) {
        checkState(currentModule == null);
        String moduleName = extractFirstArgumentName(call);
        if (moduleName == null) {
          t.report(scopeRoot, ClosureRewriteModule.INVALID_MODULE_NAMESPACE);
        } else {
          currentModule = new ModuleInfo(moduleName);
        }
      }
    }
  }

  @Override
  public void exitModule(NodeTraversal t, Node scopeRoot) {
    currentModule = null;
  }

  @Override
  public void visit(NodeTraversal t, Node n, Node parent) {
    if (currentModule == null) {
      if (NodeUtil.isCallTo(n, "goog.module")) {
        t.report(n, GOOG_MODULE_IN_NON_MODULE);
      }
      return;
    }
    JSDocInfo jsDoc = n.getJSDocInfo();
    if (jsDoc != null) {
      checkJSDoc(t, jsDoc);
    }
    switch (n.getToken()) {
      case CALL:
        Node callee = n.getFirstChild();
        if (callee.matchesQualifiedName("goog.module")
            && !currentModule.name.equals(extractFirstArgumentName(n))) {
          t.report(n, MULTIPLE_MODULES_IN_FILE);
        } else if (callee.matchesQualifiedName("goog.provide")) {
          t.report(n, MODULE_AND_PROVIDES);
        } else if (callee.matchesQualifiedName("goog.require")
            || callee.matchesQualifiedName("goog.forwardDeclare")) {
          checkRequireCall(t, n, parent);
        } else if (callee.matchesQualifiedName("goog.module.get") && t.inModuleHoistScope()) {
          t.report(n, GOOG_MODULE_USES_GOOG_MODULE_GET);
        }
        break;
      case ASSIGN: {
        if (isExportLhs(n.getFirstChild())) {
          checkModuleExport(t, n, parent);
        }
        break;
      }
      case CLASS:
      case FUNCTION:
        if (!NodeUtil.isStatement(n)) {
          break;
        }
        // fallthrough
      case VAR:
      case LET:
      case CONST:
        if (t.inModuleHoistScope() && (n.isClass() || NodeUtil.getEnclosingClass(n) == null)
            && NodeUtil.getEnclosingType(n, Token.OBJECTLIT) == null) {
          JSDocInfo jsdoc = NodeUtil.getBestJSDocInfo(n);
          if (jsdoc != null && jsdoc.isExport()) {
            t.report(n, AT_EXPORT_IN_GOOG_MODULE);
          }
        }
        break;
      case THIS:
        if (t.inModuleHoistScope()) {
          t.report(n, GOOG_MODULE_REFERENCES_THIS);
        }
        break;
      case THROW:
        if (t.inModuleHoistScope()) {
          t.report(n, GOOG_MODULE_USES_THROW);
        }
        break;
      case GETPROP:
        if (n.matchesQualifiedName(currentModule.name)) {
          t.report(n, REFERENCE_TO_MODULE_GLOBAL_NAME);
        } else if (currentModule.importsByLongRequiredName.containsKey(n.getQualifiedName())) {
          Node importLhs = currentModule.importsByLongRequiredName.get(n.getQualifiedName());
          if (importLhs == null || !importLhs.isName()) {
            t.report(n, REFERENCE_TO_FULLY_QUALIFIED_IMPORT_NAME, n.getQualifiedName());
          } else {
            t.report(n, REFERENCE_TO_SHORT_IMPORT_BY_LONG_NAME_INCLUDING_SHORT_NAME,
                n.getQualifiedName(), importLhs.getQualifiedName());
          }
        }
        break;
      default:
        break;
    }
  }

  private void checkJSDoc(NodeTraversal t, JSDocInfo jsDoc) {
    for (Node typeNode : jsDoc.getTypeNodes()) {
      checkTypeExpression(t, typeNode);
    }
  }

  private void checkTypeExpression(final NodeTraversal t, Node typeNode) {
    NodeUtil.visitPreOrder(
        typeNode,
        new NodeUtil.Visitor() {
          @Override
          public void visit(Node node) {
            if (!node.isString()) {
              return;
            }
            String type = node.getString();
            while (true) {
              if (currentModule.importsByLongRequiredName.containsKey(type)) {
                Node importLhs = currentModule.importsByLongRequiredName.get(type);
                if (importLhs == null || !importLhs.isName()) {
                  t.report(node, JSDOC_REFERENCE_TO_FULLY_QUALIFIED_IMPORT_NAME, type);
                } else if (!importLhs.getString().equals(type)) {
                  t.report(
                      node,
                      JSDOC_REFERENCE_TO_SHORT_IMPORT_BY_LONG_NAME_INCLUDING_SHORT_NAME,
                      type,
                      importLhs.getString());
                }
              }
              if (type.contains(".")) {
                type = type.substring(0, type.lastIndexOf("."));
              } else {
                return;
              }
            }
          }
        },
        Predicates.<Node>alwaysTrue());
  }

  /** Is this the LHS of a goog.module export? i.e. Either "exports" or "exports.name" */
  private boolean isExportLhs(Node lhs) {
    if (!lhs.isQualifiedName()) {
      return false;
    }
    return lhs.matchesQualifiedName("exports")
        || (lhs.isGetProp() && lhs.getFirstChild().matchesQualifiedName("exports"));
  }

  private void checkModuleExport(NodeTraversal t, Node n, Node parent) {
    checkArgument(n.isAssign());
    Node lhs = n.getFirstChild();
    checkState(isExportLhs(lhs));
    if (currentModule.defaultExportNode == null && (!t.inModuleScope() || !parent.isExprResult())) {
      // Invalid export location.
      t.report(n, EXPORT_NOT_A_MODULE_LEVEL_STATEMENT);
    }
    if (lhs.isName()) {
      if  (currentModule.defaultExportNode != null) {
        // Multiple exports
        int previousLine = currentModule.defaultExportNode.getLineno();
        t.report(n, EXPORT_REPEATED_ERROR, String.valueOf(previousLine));
      }
      currentModule.defaultExportNode = lhs;
    }
    if ((lhs.isName() || !NodeUtil.isPrototypeProperty(lhs))
        && !NodeUtil.isLegacyGoogModuleFile(NodeUtil.getEnclosingScript(n))) {
      JSDocInfo jsDoc = n.getJSDocInfo();
      if (jsDoc != null && jsDoc.isExport()) {
        t.report(n, AT_EXPORT_IN_NON_LEGACY_GOOG_MODULE);
      }
    }
  }

  private String extractFirstArgumentName(Node callNode) {
    Node firstArg = callNode.getSecondChild();
    if (firstArg != null && firstArg.isString()) {
      return firstArg.getString();
    }
    return null;
  }

  private void checkRequireCall(NodeTraversal t, Node callNode, Node parent) {
    checkState(callNode.isCall());
    switch (parent.getToken()) {
      case EXPR_RESULT:
        currentModule.importsByLongRequiredName.put(extractFirstArgumentName(callNode), parent);
        return;
      case NAME:
      case DESTRUCTURING_LHS:
        checkShortGoogRequireCall(t, callNode, parent.getParent());
        return;
      default:
        break;
    }
    t.report(callNode, REQUIRE_NOT_AT_TOP_LEVEL);
  }

  private void checkShortGoogRequireCall(NodeTraversal t, Node callNode, Node declaration) {
    if (declaration.isLet()
        && !callNode.getFirstChild().matchesQualifiedName("goog.forwardDeclare")) {
      t.report(declaration, LET_GOOG_REQUIRE);
    }
    if (!declaration.hasOneChild()) {
      t.report(declaration, ONE_REQUIRE_PER_DECLARATION);
      return;
    }
    Node lhs = declaration.getFirstChild();
    if (lhs.isDestructuringLhs()) {
      if (!isValidDestructuringImport(lhs)) {
        t.report(declaration, INVALID_DESTRUCTURING_REQUIRE);
      }
      if (callNode.getFirstChild().matchesQualifiedName("goog.forwardDeclare")) {
        t.report(lhs, INVALID_DESTRUCTURING_FORWARD_DECLARE);
      }
    } else {
      checkState(lhs.isName());
      checkShortName(t, lhs, callNode.getLastChild().getString());
    }
    currentModule.importsByLongRequiredName.put(extractFirstArgumentName(callNode), lhs);
    for (Node nameNode : NodeUtil.getLhsNodesOfDeclaration(declaration)) {
      String name = nameNode.getString();
      if (!currentModule.shortImportNames.add(name)) {
         t.report(nameNode, DUPLICATE_NAME_SHORT_REQUIRE, name);
      }
    }
  }

  private static void checkShortName(NodeTraversal t, Node shortNameNode, String namespace) {
    String shortName = shortNameNode.getString();
    String lastSegment = namespace.substring(namespace.lastIndexOf('.') + 1);
    if (shortName.equals(lastSegment) || lastSegment.isEmpty()) {
      return;
    }

    if (isUpperCase(shortName.charAt(0)) != isUpperCase(lastSegment.charAt(0))) {
      char newStartChar =
          isUpperCase(shortName.charAt(0))
              ? toLowerCase(shortName.charAt(0))
              : toUpperCase(shortName.charAt(0));
      String correctedName = newStartChar + shortName.substring(1);
      t.report(shortNameNode, INCORRECT_SHORTNAME_CAPITALIZATION, shortName, correctedName);
    }
  }

  private static boolean isValidDestructuringImport(Node destructuringLhs) {
    checkArgument(destructuringLhs.isDestructuringLhs());
    Node objectPattern = destructuringLhs.getFirstChild();
    if (!objectPattern.isObjectPattern()) {
      return false;
    }
    for (Node stringKey : objectPattern.children()) {
       if (!stringKey.isStringKey()) {
         return false;
       }
       if (stringKey.hasChildren() && !stringKey.getFirstChild().isName()) {
         return false;
       }
    }
    return true;
  }
}
