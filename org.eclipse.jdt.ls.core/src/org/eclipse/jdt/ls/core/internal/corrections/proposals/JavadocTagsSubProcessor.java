/*******************************************************************************
 * Copyright (c) 2000, 2016 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Copied from /org.eclipse.jdt.ui/src/org/eclipse/jdt/internal/ui/text/correction/JavadocTagsSubProcessor.java
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal.corrections.proposals;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.ITypeRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.StructuralPropertyDescriptor;
import org.eclipse.jdt.core.dom.TagElement;
import org.eclipse.jdt.core.dom.TextElement;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.TypeParameter;
import org.eclipse.jdt.core.dom.VariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jdt.core.manipulation.CodeGeneration;
import org.eclipse.jdt.internal.core.manipulation.StubUtility;
import org.eclipse.jdt.internal.core.manipulation.dom.ASTResolving;
import org.eclipse.jdt.internal.core.manipulation.util.Strings;
import org.eclipse.jdt.internal.corext.dom.ASTNodes;
import org.eclipse.jdt.internal.corext.dom.Bindings;
import org.eclipse.jdt.internal.ui.text.correction.IProblemLocationCore;
import org.eclipse.jdt.ls.core.internal.Messages;
import org.eclipse.jdt.ls.core.internal.StatusFactory;
import org.eclipse.jdt.ls.core.internal.corrections.CorrectionMessages;
import org.eclipse.jdt.ls.core.internal.corrections.IInvocationContext;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.TextUtilities;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.text.edits.TextEdit;
import org.eclipse.text.edits.TextEditGroup;


/**
 *
 */
public class JavadocTagsSubProcessor {

	private static final class AddJavadocCommentProposal extends CUCorrectionProposal {

		private final int fInsertPosition;
		private final String fComment;

		private AddJavadocCommentProposal(String name, ICompilationUnit cu, int relevance, String kind, int insertPosition, String comment) {
			super(name, kind, cu, null, relevance);
			fInsertPosition= insertPosition;
			fComment= comment;
		}

		@Override
		protected void addEdits(IDocument document, TextEdit rootEdit) throws CoreException {
			try {
				String lineDelimiter= TextUtilities.getDefaultLineDelimiter(document);
				final ICompilationUnit unit= getCompilationUnit();
				IRegion region= document.getLineInformationOfOffset(fInsertPosition);

				String lineContent= document.get(region.getOffset(), region.getLength());
				String indentString= Strings.getIndentString(lineContent, unit);
				String str= Strings.changeIndent(fComment, 0, unit, indentString, lineDelimiter);
				InsertEdit edit= new InsertEdit(fInsertPosition, str);
				rootEdit.addChild(edit);
				if (fComment.charAt(fComment.length() - 1) != '\n') {
					rootEdit.addChild(new InsertEdit(fInsertPosition, lineDelimiter));
					rootEdit.addChild(new InsertEdit(fInsertPosition, indentString));
				}
			} catch (BadLocationException e) {
				throw new CoreException(StatusFactory.newErrorStatus("Invalid edit", e));
			}
		}
	}

	private static final class AddMissingJavadocTagProposal extends ASTRewriteCorrectionProposal {

		private final BodyDeclaration fBodyDecl; // MethodDecl or TypeDecl
		private final ASTNode fMissingNode;

		public AddMissingJavadocTagProposal(String label, ICompilationUnit cu, BodyDeclaration bodyDecl, ASTNode missingNode, int relevance) {
			super(label, CodeActionKind.QuickFix, cu, null, relevance);
			fBodyDecl= bodyDecl;
			fMissingNode= missingNode;
		}

		@Override
		protected ASTRewrite getRewrite() throws CoreException {
			AST ast= fBodyDecl.getAST();
			ASTRewrite rewrite= ASTRewrite.create(ast);
			insertMissingJavadocTag(rewrite, fMissingNode, fBodyDecl);
			return rewrite;
		}

		private void insertMissingJavadocTag(ASTRewrite rewrite, ASTNode missingNode, BodyDeclaration bodyDecl) {
			AST ast= bodyDecl.getAST();
			Javadoc javadoc= bodyDecl.getJavadoc();
			if (javadoc == null) {
				javadoc= ast.newJavadoc();
				rewrite.set(bodyDecl, bodyDecl.getJavadocProperty(), javadoc, null);
			}

			ListRewrite tagsRewriter= rewrite.getListRewrite(javadoc, Javadoc.TAGS_PROPERTY);

			StructuralPropertyDescriptor location= missingNode.getLocationInParent();
			TagElement newTag;
			if (location == SingleVariableDeclaration.NAME_PROPERTY) {
				// normal parameter
				SingleVariableDeclaration decl= (SingleVariableDeclaration) missingNode.getParent();

				String name= ((SimpleName) missingNode).getIdentifier();
				newTag= ast.newTagElement();
				newTag.setTagName(TagElement.TAG_PARAM);
				newTag.fragments().add(ast.newSimpleName(name));

				MethodDeclaration methodDeclaration= (MethodDeclaration) bodyDecl;
				List<SingleVariableDeclaration> params= methodDeclaration.parameters();

				Set<String> sameKindLeadingNames= getPreviousParamNames(params, decl);

				List<TypeParameter> typeParams= methodDeclaration.typeParameters();
				for (int i= 0; i < typeParams.size(); i++) {
					String curr= '<' + typeParams.get(i).getName().getIdentifier() + '>';
					sameKindLeadingNames.add(curr);
				}
				insertTag(tagsRewriter, newTag, sameKindLeadingNames);
			} else if (location == TypeParameter.NAME_PROPERTY) {
				// type parameter
				TypeParameter typeParam= (TypeParameter) missingNode.getParent();

				String name= '<' + ((SimpleName) missingNode).getIdentifier() + '>';
				newTag= ast.newTagElement();
				newTag.setTagName(TagElement.TAG_PARAM);
				TextElement text= ast.newTextElement();
				text.setText(name);
				newTag.fragments().add(text);
				List<TypeParameter> params;
				if (bodyDecl instanceof TypeDeclaration typeDecl) {
					params = typeDecl.typeParameters();
				} else {
					params= ((MethodDeclaration) bodyDecl).typeParameters();
				}
				insertTag(tagsRewriter, newTag, getPreviousTypeParamNames(params, typeParam));
			} else if (location == MethodDeclaration.RETURN_TYPE2_PROPERTY) {
				newTag= ast.newTagElement();
				newTag.setTagName(TagElement.TAG_RETURN);
				insertTag(tagsRewriter, newTag, null);
			} else if (location == MethodDeclaration.THROWN_EXCEPTION_TYPES_PROPERTY) {
				newTag= ast.newTagElement();
				newTag.setTagName(TagElement.TAG_THROWS);
				TextElement excNode= ast.newTextElement();
				excNode.setText(ASTNodes.getQualifiedTypeName((Type) missingNode));
				newTag.fragments().add(excNode);
				List<Type> exceptions= ((MethodDeclaration) bodyDecl).thrownExceptionTypes();
				insertTag(tagsRewriter, newTag, getPreviousExceptionNames(exceptions, missingNode));
			} else {
				Assert.isTrue(false, "AddMissingJavadocTagProposal: unexpected node location"); //$NON-NLS-1$
				return;
			}

			TextElement textElement= ast.newTextElement();
			textElement.setText(""); //$NON-NLS-1$
			newTag.fragments().add(textElement);

			if (bodyDecl.getJavadoc() == null) {
				// otherwise the linked position spans over a line delimiter
				newTag.fragments().add(ast.newTextElement());
			}
		}
	}

	private static final class AddAllMissingJavadocTagsProposal extends ASTRewriteCorrectionProposal {

		private final BodyDeclaration fBodyDecl;

		public AddAllMissingJavadocTagsProposal(String label, ICompilationUnit cu, BodyDeclaration bodyDecl, int relevance) {
			super(label, CodeActionKind.QuickFix, cu, null, relevance);
			fBodyDecl= bodyDecl;
		}

		@Override
		protected ASTRewrite getRewrite() throws CoreException {
			ASTRewrite rewrite= ASTRewrite.create(fBodyDecl.getAST());
			if (fBodyDecl instanceof MethodDeclaration methodDecl) {
				insertAllMissingMethodTags(rewrite, methodDecl);
			} else {
				insertAllMissingTypeTags(rewrite, (TypeDeclaration) fBodyDecl);
			}
			return rewrite;
		}

		private void insertAllMissingMethodTags(ASTRewrite rewriter, MethodDeclaration methodDecl) {
			AST ast= methodDecl.getAST();
			Javadoc javadoc= methodDecl.getJavadoc();
			ListRewrite tagsRewriter= rewriter.getListRewrite(javadoc, Javadoc.TAGS_PROPERTY);

			List<TypeParameter> typeParams= methodDecl.typeParameters();
			ASTNode root= methodDecl.getRoot();
			if (root instanceof CompilationUnit unit) {
				ITypeRoot typeRoot = unit.getTypeRoot();
				if (typeRoot != null && !StubUtility.shouldGenerateMethodTypeParameterTags(typeRoot.getJavaProject())) {
					typeParams= Collections.emptyList();
				}
			}
			List<String> typeParamNames= new ArrayList<>();
			for (int i= typeParams.size() - 1; i >= 0 ; i--) {
				TypeParameter decl= typeParams.get(i);
				String name= '<' + decl.getName().getIdentifier() + '>';
				if (findTag(javadoc, TagElement.TAG_PARAM, name) == null) {
					TagElement newTag= ast.newTagElement();
					newTag.setTagName(TagElement.TAG_PARAM);
					TextElement text= ast.newTextElement();
					text.setText(name);
					newTag.fragments().add(text);
					insertTabStop(rewriter, newTag.fragments(), "typeParam" + i); //$NON-NLS-1$
					insertTag(tagsRewriter, newTag, getPreviousTypeParamNames(typeParams, decl));
				}
				typeParamNames.add(name);
			}
			List<SingleVariableDeclaration> params= methodDecl.parameters();
			for (int i= params.size() - 1; i >= 0 ; i--) {
				SingleVariableDeclaration decl= params.get(i);
				String name= decl.getName().getIdentifier();
				if (findTag(javadoc, TagElement.TAG_PARAM, name) == null) {
					TagElement newTag= ast.newTagElement();
					newTag.setTagName(TagElement.TAG_PARAM);
					newTag.fragments().add(ast.newSimpleName(name));
					insertTabStop(rewriter, newTag.fragments(), "methParam" + i); //$NON-NLS-1$
					Set<String> sameKindLeadingNames= getPreviousParamNames(params, decl);
					sameKindLeadingNames.addAll(typeParamNames);
					insertTag(tagsRewriter, newTag, sameKindLeadingNames);
				}
			}
			if (!methodDecl.isConstructor()) {
				Type type= methodDecl.getReturnType2();
				if (!type.isPrimitiveType() || (((PrimitiveType) type).getPrimitiveTypeCode() != PrimitiveType.VOID)) {
					if (findTag(javadoc, TagElement.TAG_RETURN, null) == null) {
						TagElement newTag= ast.newTagElement();
						newTag.setTagName(TagElement.TAG_RETURN);
						insertTabStop(rewriter, newTag.fragments(), "return"); //$NON-NLS-1$
						insertTag(tagsRewriter, newTag, null);
					}
				}
			}
			List<Type> thrownExceptions= methodDecl.thrownExceptionTypes();
			for (int i= thrownExceptions.size() - 1; i >= 0 ; i--) {
				Type exception= thrownExceptions.get(i);
				ITypeBinding binding= exception.resolveBinding();
				if (binding != null) {
					String name= binding.getName();
					if (findThrowsTag(javadoc, name) == null) {
						TagElement newTag= ast.newTagElement();
						newTag.setTagName(TagElement.TAG_THROWS);
						TextElement excNode= ast.newTextElement();
						excNode.setText(ASTNodes.getQualifiedTypeName(exception));
						newTag.fragments().add(excNode);
						insertTabStop(rewriter, newTag.fragments(), "exception" + i); //$NON-NLS-1$
						insertTag(tagsRewriter, newTag, getPreviousExceptionNames(thrownExceptions, exception));
					}
				}
			}
		}

		private void insertAllMissingTypeTags(ASTRewrite rewriter, TypeDeclaration typeDecl) {
			AST ast= typeDecl.getAST();
			Javadoc javadoc= typeDecl.getJavadoc();
			ListRewrite tagsRewriter= rewriter.getListRewrite(javadoc, Javadoc.TAGS_PROPERTY);

			List<TypeParameter> typeParams= typeDecl.typeParameters();
			for (int i= typeParams.size() - 1; i >= 0; i--) {
				TypeParameter decl= typeParams.get(i);
				String name= '<' + decl.getName().getIdentifier() + '>';
				if (findTag(javadoc, TagElement.TAG_PARAM, name) == null) {
					TagElement newTag= ast.newTagElement();
					newTag.setTagName(TagElement.TAG_PARAM);
					TextElement text= ast.newTextElement();
					text.setText(name);
					newTag.fragments().add(text);
					insertTabStop(rewriter, newTag.fragments(), "typeParam" + i); //$NON-NLS-1$
					insertTag(tagsRewriter, newTag, getPreviousTypeParamNames(typeParams, decl));
				}
			}
		}

		private void insertTabStop(ASTRewrite rewriter, List<ASTNode> fragments, String linkedName) {
			TextElement textElement= rewriter.getAST().newTextElement();
			textElement.setText(""); //$NON-NLS-1$
			fragments.add(textElement);
		}

	}

	public static void getMissingJavadocTagProposals(IInvocationContext context, IProblemLocationCore problem, Collection<ChangeCorrectionProposal> proposals) {
		ASTNode node= problem.getCoveringNode(context.getASTRoot());
		if (node == null) {
			return;
		}
		node= ASTNodes.getNormalizedNode(node);

		BodyDeclaration bodyDeclaration= ASTResolving.findParentBodyDeclaration(node);
		if (bodyDeclaration == null) {
			return;
		}
		Javadoc javadoc= bodyDeclaration.getJavadoc();
		if (javadoc == null) {
			return;
		}

		String label;
		StructuralPropertyDescriptor location= node.getLocationInParent();
		if (location == SingleVariableDeclaration.NAME_PROPERTY) {
			label= CorrectionMessages.JavadocTagsSubProcessor_addjavadoc_paramtag_description;
			if (node.getParent().getLocationInParent() != MethodDeclaration.PARAMETERS_PROPERTY) {
				return; // paranoia checks
			}
		} else if (location == TypeParameter.NAME_PROPERTY) {
			label= CorrectionMessages.JavadocTagsSubProcessor_addjavadoc_paramtag_description;
			StructuralPropertyDescriptor parentLocation= node.getParent().getLocationInParent();
			if (parentLocation != MethodDeclaration.TYPE_PARAMETERS_PROPERTY && parentLocation != TypeDeclaration.TYPE_PARAMETERS_PROPERTY) {
				return; // paranoia checks
			}
		} else if (location == MethodDeclaration.RETURN_TYPE2_PROPERTY) {
			label= CorrectionMessages.JavadocTagsSubProcessor_addjavadoc_returntag_description;
		} else if (location == MethodDeclaration.THROWN_EXCEPTION_TYPES_PROPERTY) {
			label= CorrectionMessages.JavadocTagsSubProcessor_addjavadoc_throwstag_description;
		} else {
			return;
		}
		ASTRewriteCorrectionProposal proposal= new AddMissingJavadocTagProposal(label, context.getCompilationUnit(), bodyDeclaration, node, IProposalRelevance.ADD_MISSING_TAG);
		proposals.add(proposal);

		String label2= CorrectionMessages.JavadocTagsSubProcessor_addjavadoc_allmissing_description;
		ASTRewriteCorrectionProposal addAllMissing= new AddAllMissingJavadocTagsProposal(label2, context.getCompilationUnit(), bodyDeclaration, IProposalRelevance.ADD_ALL_MISSING_TAGS);
		proposals.add(addAllMissing);
	}

	public static void getUnusedAndUndocumentedParameterOrExceptionProposals(IInvocationContext context,
			IProblemLocationCore problem, Collection<ChangeCorrectionProposal> proposals) {
		ICompilationUnit cu= context.getCompilationUnit();
		IJavaProject project= cu.getJavaProject();

		if (!JavaCore.ENABLED.equals(project.getOption(JavaCore.COMPILER_DOC_COMMENT_SUPPORT, true))) {
			return;
		}

		int problemId= problem.getProblemId();
		boolean isUnusedTypeParam= problemId == IProblem.UnusedTypeParameter;
		boolean isUnusedParam= problemId == IProblem.ArgumentIsNeverUsed || isUnusedTypeParam;
		String key= isUnusedParam ? JavaCore.COMPILER_PB_UNUSED_PARAMETER_INCLUDE_DOC_COMMENT_REFERENCE : JavaCore.COMPILER_PB_UNUSED_DECLARED_THROWN_EXCEPTION_INCLUDE_DOC_COMMENT_REFERENCE;

		if (!JavaCore.ENABLED.equals(project.getOption(key, true))) {
			return;
		}

		ASTNode node= problem.getCoveringNode(context.getASTRoot());
		if (node == null) {
			return;
		}

		BodyDeclaration bodyDecl= ASTResolving.findParentBodyDeclaration(node);
		if (bodyDecl == null || ASTResolving.getParentMethodOrTypeBinding(bodyDecl) == null) {
			return;
		}

		String label;
		if (isUnusedTypeParam) {
			label= CorrectionMessages.JavadocTagsSubProcessor_document_type_parameter_description;
		} else if (isUnusedParam) {
			label= CorrectionMessages.JavadocTagsSubProcessor_document_parameter_description;
		} else {
			node= ASTNodes.getNormalizedNode(node);
			label= CorrectionMessages.JavadocTagsSubProcessor_document_exception_description;
		}
		ASTRewriteCorrectionProposal proposal= new AddMissingJavadocTagProposal(label, context.getCompilationUnit(), bodyDecl, node, IProposalRelevance.DOCUMENT_UNUSED_ITEM);
		proposals.add(proposal);
	}

	public static void getMissingJavadocCommentProposals(IInvocationContext context, ASTNode node,
			Collection<ChangeCorrectionProposal> proposals, String kind) throws CoreException {
		if (node == null) {
			return;
		}
		BodyDeclaration declaration= ASTResolving.findParentBodyDeclaration(node);
		if (declaration == null || declaration.getJavadoc() != null) {
			return;
		}
		ICompilationUnit cu= context.getCompilationUnit();
		ITypeBinding binding= Bindings.getBindingOfParentType(declaration);
		if (binding == null) {
			return;
		}

		if (declaration instanceof MethodDeclaration methodDecl) {
			IMethodBinding methodBinding= methodDecl.resolveBinding();
			IMethodBinding overridden= null;
			if (methodBinding != null) {
				overridden= Bindings.findOverriddenMethod(methodBinding, true);
			}
			// See org.eclipse.jdt.internal.core.manipulation.StubUtility.getMethodComment()
			// For a method which is not a constructor and has a null return type, an NPE will throw.
			// The following block will guard this.
			if (!methodDecl.isConstructor() && methodDecl.getReturnType2() == null) {
				return;
			}
			String string = CodeGeneration.getMethodComment(cu, binding.getName(), methodDecl, overridden,
					String.valueOf('\n'));
			String methodName = methodDecl.getName().getIdentifier();
			if (string != null && methodName != null) {
				String label= Messages.format(CorrectionMessages.JavadocTagsSubProcessor_addjavadoc_method_description, methodName);
				proposals.add(new AddJavadocCommentProposal(label, cu, IProposalRelevance.ADD_JAVADOC_METHOD, kind, declaration.getStartPosition(), string));
			}
		} else if (declaration instanceof AbstractTypeDeclaration) {
			String typeQualifiedName= Bindings.getTypeQualifiedName(binding);
			String[] typeParamNames;
			if (declaration instanceof TypeDeclaration typeDecl) {
				List<TypeParameter> typeParams = typeDecl.typeParameters();
				typeParamNames= new String[typeParams.size()];
				for (int i= 0; i < typeParamNames.length; i++) {
					typeParamNames[i]= (typeParams.get(i)).getName().getIdentifier();
				}
			} else {
				typeParamNames= new String[0];
			}
			String string = CodeGeneration.getTypeComment(cu, typeQualifiedName, typeParamNames,
					String.valueOf('\n'));

			if (string != null && typeQualifiedName != null) {
				String label= Messages.format(CorrectionMessages.JavadocTagsSubProcessor_addjavadoc_type_description, typeQualifiedName);
				proposals.add(new AddJavadocCommentProposal(label, cu, IProposalRelevance.ADD_JAVADOC_TYPE, kind, declaration.getStartPosition(), string));
			}
		} else if (declaration instanceof FieldDeclaration fieldDecl) {
			String comment= "/**\n *\n */\n"; //$NON-NLS-1$
			String fieldName= null;
			List<VariableDeclarationFragment> fragments = fieldDecl.fragments();
			if (fragments != null && fragments.size() > 0) {
				VariableDeclaration decl= fragments.get(0);
				fieldName= decl.getName().getIdentifier();
				String typeName= binding.getName();
				comment = CodeGeneration.getFieldComment(cu, typeName, fieldName, String.valueOf('\n'));
			}
			if (comment != null && fieldName != null) {
				String label= Messages.format(CorrectionMessages.JavadocTagsSubProcessor_addjavadoc_field_description, fieldName);
				proposals.add(new AddJavadocCommentProposal(label, cu, IProposalRelevance.ADD_JAVADOC_FIELD, kind, declaration.getStartPosition(), comment));
			}
		} else if (declaration instanceof EnumConstantDeclaration enumDecl) {
			String id= enumDecl.getName().getIdentifier();
			String comment = CodeGeneration.getFieldComment(cu, binding.getName(), id, String.valueOf('\n'));
			if (comment != null && id != null) {
				String label=Messages.format(CorrectionMessages.JavadocTagsSubProcessor_addjavadoc_enumconst_description, id);
				proposals.add(new AddJavadocCommentProposal(label, cu, IProposalRelevance.ADD_JAVADOC_ENUM, kind, declaration.getStartPosition(), comment));
			}
		}
	}

	public static Set<String> getPreviousTypeParamNames(List<TypeParameter> typeParams, ASTNode missingNode) {
		Set<String> previousNames=  new HashSet<>();
		for (int i = 0; i < typeParams.size(); i++) {
			TypeParameter curr= typeParams.get(i);
			if (curr == missingNode) {
				return previousNames;
			}
			previousNames.add('<' + curr.getName().getIdentifier() + '>');
		}
		return previousNames;
	}

	private static Set<String> getPreviousParamNames(List<SingleVariableDeclaration> params, ASTNode missingNode) {
		Set<String> previousNames=  new HashSet<>();
		for (int i = 0; i < params.size(); i++) {
			SingleVariableDeclaration curr= params.get(i);
			if (curr == missingNode) {
				return previousNames;
			}
			previousNames.add(curr.getName().getIdentifier());
		}
		return previousNames;
	}

	private static Set<String> getPreviousExceptionNames(List<Type> list, ASTNode missingNode) {
		Set<String> previousNames=  new HashSet<>();
		for (int i= 0; i < list.size() && missingNode != list.get(i); i++) {
			Type curr= list.get(i);
			previousNames.add(ASTNodes.getTypeName(curr));
		}
		return previousNames;
	}

	public static TagElement findTag(Javadoc javadoc, String name, String arg) {
		List<TagElement> tags= javadoc.tags();
		int nTags= tags.size();
		for (int i= 0; i < nTags; i++) {
			TagElement curr= tags.get(i);
			if (name.equals(curr.getTagName())) {
				if (arg != null) {
					String argument= getArgument(curr);
					if (arg.equals(argument)) {
						return curr;
					}
				} else {
					return curr;
				}
			}
		}
		return null;
	}

	public static TagElement findParamTag(Javadoc javadoc, String arg) {
		List<TagElement> tags= javadoc.tags();
		int nTags= tags.size();
		for (int i= 0; i < nTags; i++) {
			TagElement curr= tags.get(i);
			String currName= curr.getTagName();
			if (TagElement.TAG_PARAM.equals(currName)) {
				String argument= getArgument(curr);
				if (arg.equals(argument)) {
					return curr;
				}
			}
		}
		return null;
	}


	public static TagElement findThrowsTag(Javadoc javadoc, String arg) {
		List<TagElement> tags= javadoc.tags();
		int nTags= tags.size();
		for (int i= 0; i < nTags; i++) {
			TagElement curr= tags.get(i);
			String currName= curr.getTagName();
			if (TagElement.TAG_THROWS.equals(currName) || TagElement.TAG_EXCEPTION.equals(currName)) {
				String argument= getArgument(curr);
				if (arg.equals(argument)) {
					return curr;
				}
			}
		}
		return null;
	}

	public static void insertTag(ListRewrite rewriter, TagElement newElement, Set<String> sameKindLeadingNames) {
		insertTag(rewriter, newElement, sameKindLeadingNames, null);
	}

	public static void insertTag(ListRewrite rewriter, TagElement newElement, Set<String> sameKindLeadingNames, TextEditGroup groupDescription) {
		List<? extends ASTNode> tags= rewriter.getRewrittenList();

		String insertedTagName= newElement.getTagName();

		ASTNode after= null;
		int tagRanking= getTagRanking(insertedTagName);
		for (int i= tags.size() - 1; i >= 0; i--) {
			TagElement curr= (TagElement) tags.get(i);
			String tagName= curr.getTagName();
			if (tagName == null || tagRanking > getTagRanking(tagName)) {
				after= curr;
				break;
			}
			if (sameKindLeadingNames != null && isSameTag(insertedTagName, tagName)) {
				String arg= getArgument(curr);
				if (arg != null && sameKindLeadingNames.contains(arg)) {
					after= curr;
					break;
				}
			}
		}
		if (after != null) {
			rewriter.insertAfter(newElement, after, groupDescription);
		} else {
			rewriter.insertFirst(newElement, groupDescription);
		}
	}

	private static boolean isSameTag(String insertedTagName, String tagName) {
		if (insertedTagName.equals(tagName)) {
			return true;
		}
		if (TagElement.TAG_EXCEPTION.equals(tagName)) {
			return TagElement.TAG_THROWS.equals(insertedTagName);
		}
		return false;
	}

	private static String[] TAG_ORDER= { // see http://www.oracle.com/technetwork/java/javase/documentation/index-137868.html#orderoftags
			TagElement.TAG_AUTHOR,
			TagElement.TAG_VERSION,
			TagElement.TAG_PARAM,
			TagElement.TAG_RETURN,
			TagElement.TAG_THROWS, // synonym to TAG_EXCEPTION
			TagElement.TAG_SEE,
			TagElement.TAG_SINCE,
			TagElement.TAG_SERIAL,
			TagElement.TAG_DEPRECATED
	};

	private static int getTagRanking(String tagName) {
		if (tagName.equals(TagElement.TAG_EXCEPTION)) {
			tagName= TagElement.TAG_THROWS;
		}
		for (int i= 0; i < TAG_ORDER.length; i++) {
			if (tagName.equals(TAG_ORDER[i])) {
				return i;
			}
		}
		return TAG_ORDER.length;
	}

	private static String getArgument(TagElement curr) {
		List<? extends ASTNode> fragments= curr.fragments();
		if (!fragments.isEmpty()) {
			Object first= fragments.get(0);
			if (first instanceof Name name) {
				return ASTNodes.getSimpleNameIdentifier(name);
			} else if (first instanceof TextElement firstTextElement && TagElement.TAG_PARAM.equals(curr.getTagName())) {
				String text = firstTextElement.getText();
				if ("<".equals(text) && fragments.size() >= 3) { //$NON-NLS-1$
					Object second= fragments.get(1);
					Object third= fragments.get(2);
					if (second instanceof Name secondName && third instanceof TextElement thirdTextElement && ">".equals(thirdTextElement.getText())) { //$NON-NLS-1$
						return '<' + ASTNodes.getSimpleNameIdentifier(secondName) + '>';
					}
				} else if (text.startsWith(String.valueOf('<')) && text.endsWith(String.valueOf('>')) && text.length() > 2) {
					return text.substring(1, text.length() - 1);
				}
			}
		}
		return null;
	}

	public static void getRemoveJavadocTagProposals(IInvocationContext context, IProblemLocationCore problem,
			Collection<ChangeCorrectionProposal> proposals) {
		ASTNode node= problem.getCoveringNode(context.getASTRoot());
		while (node != null && !(node instanceof TagElement)) {
			node= node.getParent();
		}
		if (node == null) {
			return;
		}
		ASTRewrite rewrite= ASTRewrite.create(node.getAST());
		rewrite.remove(node, null);

		String label= CorrectionMessages.JavadocTagsSubProcessor_removetag_description;
		proposals.add(new ASTRewriteCorrectionProposal(label, CodeActionKind.QuickFix, context.getCompilationUnit(), rewrite,
				IProposalRelevance.REMOVE_TAG));
	}

	public static void getInvalidQualificationProposals(IInvocationContext context, IProblemLocationCore problem,
			Collection<ChangeCorrectionProposal> proposals) {
		ASTNode node= problem.getCoveringNode(context.getASTRoot());
		if (!(node instanceof Name)) {
			return;
		}
		Name name= (Name) node;
		IBinding binding= name.resolveBinding();
		if (!(binding instanceof ITypeBinding)) {
			return;
		}
		ITypeBinding typeBinding= (ITypeBinding)binding;

		AST ast= node.getAST();
		ASTRewrite rewrite= ASTRewrite.create(ast);
		rewrite.replace(name, ast.newName(typeBinding.getQualifiedName()), null);

		String label= CorrectionMessages.JavadocTagsSubProcessor_qualifylinktoinner_description;
		ASTRewriteCorrectionProposal proposal = new ASTRewriteCorrectionProposal(label, CodeActionKind.QuickFix, context.getCompilationUnit(),
				rewrite, IProposalRelevance.QUALIFY_INNER_TYPE_NAME);

		proposals.add(proposal);
	}
}
