/*******************************************************************************
 * Copyright (c) 2003, 2009 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.debug.core.breakpoints;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeMemberDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.ArrayAccess;
import org.eclipse.jdt.core.dom.ArrayCreation;
import org.eclipse.jdt.core.dom.ArrayInitializer;
import org.eclipse.jdt.core.dom.ArrayType;
import org.eclipse.jdt.core.dom.AssertStatement;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BlockComment;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.BreakStatement;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.ContinueStatement;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.EmptyStatement;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.Initializer;
import org.eclipse.jdt.core.dom.InstanceofExpression;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.LabeledStatement;
import org.eclipse.jdt.core.dom.LineComment;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.MemberRef;
import org.eclipse.jdt.core.dom.MemberValuePair;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.MethodRef;
import org.eclipse.jdt.core.dom.MethodRefParameter;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.QualifiedType;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.SwitchStatement;
import org.eclipse.jdt.core.dom.SynchronizedStatement;
import org.eclipse.jdt.core.dom.TagElement;
import org.eclipse.jdt.core.dom.TextElement;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.ThrowStatement;
import org.eclipse.jdt.core.dom.TryStatement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclarationStatement;
import org.eclipse.jdt.core.dom.TypeLiteral;
import org.eclipse.jdt.core.dom.TypeParameter;
import org.eclipse.jdt.core.dom.VariableDeclarationExpression;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.WhileStatement;
import org.eclipse.jdt.core.dom.WildcardType;
import org.eclipse.jdt.core.dom.PrefixExpression.Operator;

/**
 * Compute a valid location where to put a breakpoint from an JDOM CompilationUnit.
 * The result is the first valid location with a line number greater or equals than the given position.
 */
public class ValidBreakpointLocationLocator extends ASTVisitor {
	
	public static final int LOCATION_NOT_FOUND= 0;
	public static final int LOCATION_LINE= 1;
	public static final int LOCATION_METHOD= 2;
	public static final int LOCATION_FIELD= 3;
	
	private CompilationUnit fCompilationUnit;
	private int fLineNumber;
	private boolean fBindingsResolved;
	private boolean fNeedBindings = false;
	private boolean fBestMatch;

	private int fLocationType;
	private boolean fLocationFound;
	private String fTypeName;
	private int fLineLocation;
	private int fMemberOffset;
    private List fLabels;

	/**
	 * @param compilationUnit the JDOM CompilationUnit of the source code.
	 * @param lineNumber the line number in the source code where to put the breakpoint.
	 * @param bestMatch if <code>true</code> look for the best match, otherwise look only for a valid line
	 */
	public ValidBreakpointLocationLocator(CompilationUnit compilationUnit, int lineNumber, boolean bindingsResolved, boolean bestMatch) {
		fCompilationUnit= compilationUnit;
		fLineNumber= lineNumber;
		fBindingsResolved= bindingsResolved;
		fBestMatch= bestMatch;
		fLocationFound= false;
	}
	
	/**
	 * Returns whether binding information would be helpful in validating a breakpoint
	 * location. If this locator makes a pass of the tree and determines that binding
	 * information would be helpful but was not available, this method returns
	 * <code>true</code>.
	 * 
	 * @return whether binding information would be helpful in validating a breakpoint location
	 */
	public boolean isBindingsRequired() {
		return fNeedBindings;
	}
	
	/**
	 * Return the type of the valid location found
	 * @return one of LOCATION_NOT_FOUND, LOCATION_LINE, LOCATION_METHOD or LOCATION_FIELD
	 */
	public int getLocationType() {
		return fLocationType;
	}
	
	/**
	 * Return of the type where the valid location is.
	 */
	public String getFullyQualifiedTypeName() {
		return fTypeName;
	}
	
	/**
	 * Return the line number of the computed valid location, if the location type is LOCATION_LINE.
	 */
	public int getLineLocation() {
		if (fLocationType == LOCATION_LINE) {
			return fLineLocation;
		}
		return -1;
	}
	
	/**
	 * Return the offset of the member which is the valid location,
	 * if the location type is LOCATION_METHOD or LOCATION_FIELD.
	 */
	public int getMemberOffset() {
		return fMemberOffset;
	}
	
	/**
	 * Compute the name of the type which contains this node.
	 * <br><br>
	 * Delegates to the old method of computing the type name if bindings are not available.
	 * @see #computeTypeName0(ASTNode)
	 * @since 3.6
	 */
	private String computeTypeName(ASTNode node) {
		AbstractTypeDeclaration type = null;
		while (!(node instanceof CompilationUnit)) {
			if (node instanceof AbstractTypeDeclaration) {
				type = (AbstractTypeDeclaration) node;
				break;
			}
			node = node.getParent();
		}
		if(type != null) {
			ITypeBinding binding = type.resolveBinding();
			if(binding != null) {
				return binding.getBinaryName();
			}
		}
		return computeTypeName0(node);
	}

	/**
	 * Fall back to compute the type name if bindings are not resolved
	 * @param node
	 * @return the computed type name
	 */
	String computeTypeName0(ASTNode node) {
		String typeName = null;
		while (!(node instanceof CompilationUnit)) {
			if (node instanceof AbstractTypeDeclaration) {
				String identifier= ((AbstractTypeDeclaration)node).getName().getIdentifier();
				if (typeName == null) {
					typeName= identifier;
				} else {
					typeName= identifier + "$" + typeName; //$NON-NLS-1$
				}
			}
			node= node.getParent();
		}
		PackageDeclaration packageDecl= ((CompilationUnit)node).getPackage();
		String packageIdentifier= ""; //$NON-NLS-1$
		if (packageDecl != null) {
			Name packageName= packageDecl.getName();
			while (packageName.isQualifiedName()) {
				QualifiedName qualifiedName= (QualifiedName) packageName;
				packageIdentifier= qualifiedName.getName().getIdentifier() + "." + packageIdentifier; //$NON-NLS-1$
				packageName= qualifiedName.getQualifier();
			}
			packageIdentifier= ((SimpleName)packageName).getIdentifier() + "." + packageIdentifier; //$NON-NLS-1$
		}
		return packageIdentifier + typeName;
	}
	
	/**
	 * Return <code>true</code> if this node children may contain a valid location
	 * for the breakpoint.
	 * @param node the node.
	 * @param isCode true indicated that the first line of the given node always
	 *	contains some executable code, even if split in multiple lines.
	 */
	private boolean visit(ASTNode node, boolean isCode) {
		// if we already found a correct location
		// no need to check the element inside.
		if (fLocationFound) {
			return false;
		}
		int startPosition= node.getStartPosition();
		int endLine= lineNumber(startPosition + node.getLength() - 1);
		// if the position is not in this part of the code
		// no need to check the element inside.
		if (endLine < fLineNumber) {
			return false;
		}
		// if the first line of this node always represents some executable code and the
		// breakpoint is requested on this line or on a previous line, this is a valid 
		// location
		int startLine = lineNumber(startPosition);
		if (isCode && (fLineNumber <= startLine)) {
			fLineLocation= startLine;
			fLocationFound= true;
			fLocationType= LOCATION_LINE;
			fTypeName= computeTypeName(node);
			return false;
		}
		return true;
	}
	
	private boolean isReplacedByConstantValue(Expression node) {
		switch (node.getNodeType()) {
			// literals are constant
			case ASTNode.BOOLEAN_LITERAL:
			case ASTNode.CHARACTER_LITERAL:
			case ASTNode.NUMBER_LITERAL:
			case ASTNode.STRING_LITERAL:
				return true;
			case ASTNode.SIMPLE_NAME:
			case ASTNode.QUALIFIED_NAME:
				return isReplacedByConstantValue((Name)node);
			case ASTNode.FIELD_ACCESS:
				return isReplacedByConstantValue((FieldAccess)node);
			case ASTNode.SUPER_FIELD_ACCESS:
				return isReplacedByConstantValue((SuperFieldAccess)node);
			case ASTNode.INFIX_EXPRESSION:
				return isReplacedByConstantValue((InfixExpression)node);
			case ASTNode.PREFIX_EXPRESSION:
				return isReplacedByConstantValue((PrefixExpression)node);
			case ASTNode.CAST_EXPRESSION:
				return isReplacedByConstantValue(((CastExpression)node).getExpression());
			default:
				return false;
		}
	}
	
	private boolean isReplacedByConstantValue(InfixExpression node) {
		// if all operands are constant value, the expression is replaced by a constant value
		if (!(isReplacedByConstantValue(node.getLeftOperand()) && isReplacedByConstantValue(node.getRightOperand()))) {
			return false;
		}
		if (node.hasExtendedOperands()) {
			for (Iterator iter = node.extendedOperands().iterator(); iter.hasNext(); ) {
				if (!isReplacedByConstantValue((Expression) iter.next())) {
					return false;
				}
			}
		}
		return true;
	}
	
	private boolean isReplacedByConstantValue(PrefixExpression node) {
		// for '-', '+', '~' and '!', if the operand is a constant value,
		// the expression is replaced by a constant value
		Operator operator = node.getOperator();
		if (operator != PrefixExpression.Operator.INCREMENT && operator != PrefixExpression.Operator.DECREMENT) {
			return isReplacedByConstantValue(node.getOperand());
		}
		return false;
	}
	
	private boolean isReplacedByConstantValue(Name node) {
		if (!fBindingsResolved) {
			fNeedBindings = true;
			return false;
		}
		// if node is a variable with a constant value (static final field)
		IBinding binding= node.resolveBinding();
		if (binding != null && binding.getKind() == IBinding.VARIABLE) {
			return ((IVariableBinding)binding).getConstantValue() != null;
		}
		return false;
	}
	
	private boolean isReplacedByConstantValue(FieldAccess node) {
		if (!fBindingsResolved) {
			fNeedBindings = true;
			return false;
		}
		// if the node is 'this.<field>', and the field is static final
		Expression expression= node.getExpression();
		IVariableBinding binding= node.resolveFieldBinding();
		if (binding != null && expression.getNodeType() == ASTNode.THIS_EXPRESSION) {
			return binding.getConstantValue() != null;
		}
		return false;
	}
	
	private boolean isReplacedByConstantValue(SuperFieldAccess node) {
		if (!fBindingsResolved) {
			fNeedBindings = true;
			return false;
		}
		// if the field is static final
		IVariableBinding binding= node.resolveFieldBinding();
		if (binding != null) {
			return binding.getConstantValue() != null;
		}
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.AnnotationTypeDeclaration)
	 */
	public boolean visit(AnnotationTypeDeclaration node) {
		if (visit(node, false)) {
			List bodyDeclaration= node.bodyDeclarations();
			for (Iterator iter= bodyDeclaration.iterator(); iter.hasNext();) {
				((BodyDeclaration)iter.next()).accept(this);
			}
		}
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.AnnotationTypeMemberDeclaration)
	 */
	public boolean visit(AnnotationTypeMemberDeclaration node) {
		return false;
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.AnonymousClassDeclaration)
	 */
	public boolean visit(AnonymousClassDeclaration node) {
		return visit(node, false);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.ArrayAccess)
	 */
	public boolean visit(ArrayAccess node) {
		return visit(node, true);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.ArrayCreation)
	 */
	public boolean visit(ArrayCreation node) {
		return visit(node, node.getInitializer() == null);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.ArrayInitializer)
	 */
	public boolean visit(ArrayInitializer node) {
		return visit(node, true);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.ArrayType)
	 */
	public boolean visit(ArrayType node) {
		return false;
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.AssertStatement)
	 */
	public boolean visit(AssertStatement node) {
		return visit(node, true);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.Assignment)
	 */
	public boolean visit(Assignment node) {
		if (visit(node, false)) {
			// if the left hand side represent a local variable, or a static field
			// and the breakpoint was requested on a line before the line where
			// starts the assigment, set the location to be the first executable
			// instruction of the right hand side, as it will be the first part of
			// this assigment to be executed
			Expression leftHandSide= node.getLeftHandSide();
			if (leftHandSide instanceof Name) {
				int startLine = lineNumber(node.getStartPosition());
				if (fLineNumber < startLine) {
					if (fBindingsResolved) {
						IVariableBinding binding= (IVariableBinding)((Name)leftHandSide).resolveBinding();
						if (binding != null && (!binding.isField() || Modifier.isStatic(binding.getModifiers())))  {
							node.getRightHandSide().accept(this);
						}
					} else {
						fNeedBindings = true;
					}
				}
			}
			return true;
		}
		return false;
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.Block)
	 */
	public boolean visit(Block node) {
		if (visit(node, false)) {
			if (node.statements().isEmpty() && node.getParent().getNodeType() == ASTNode.METHOD_DECLARATION) {
				// in case of an empty method, set the breakpoint on the last line of the empty block.
				fLineLocation= lineNumber(node.getStartPosition() + node.getLength() - 1);
				fLocationFound= true;
				fLocationType= LOCATION_LINE;
				fTypeName= computeTypeName(node);
				return false;
			}
			return true;
		}
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.BlockComment)
	 */
	public boolean visit(BlockComment node) {
		return false;
	}
	
	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.BooleanLiteral)
	 */
	public boolean visit(BooleanLiteral node) {
		return visit(node, true);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.BreakStatement)
	 */
	public boolean visit(BreakStatement node) {
		return visit(node, true);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.CastExpression)
	 */
	public boolean visit(CastExpression node) {
		return visit(node, true);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.CatchClause)
	 */
	public boolean visit(CatchClause node) {
		return visit(node, false);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.CharacterLiteral)
	 */
	public boolean visit(CharacterLiteral node) {
		return visit(node, true);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.ClassInstanceCreation)
	 */
	public boolean visit(ClassInstanceCreation node) {
		return visit(node, true);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.CompilationUnit)
	 */
	public boolean visit(CompilationUnit node) {
		return visit(node, false);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.ConditionalExpression)
	 */
	public boolean visit(ConditionalExpression node) {
		return visit(node, true);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.ConstructorInvocation)
	 */
	public boolean visit(ConstructorInvocation node) {
		return visit(node, true);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.ContinueStatement)
	 */
	public boolean visit(ContinueStatement node) {
		return visit(node, true);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.DoStatement)
	 */
	public boolean visit(DoStatement node) {
		return visit(node, false);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.EmptyStatement)
	 */
	public boolean visit(EmptyStatement node) {
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.EnhancedForStatement)
	 */
	public boolean visit(EnhancedForStatement node) {
		if (visit(node, false)) {
			node.getExpression().accept(this);
			node.getBody().accept(this);
		}
		return false;
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.EnumConstantDeclaration)
	 */
	public boolean visit(EnumConstantDeclaration node) {
		if (visit(node, false)) {
			List arguments= node.arguments();
			for (Iterator iter= arguments.iterator(); iter.hasNext();) {
				((Expression)iter.next()).accept(this);
			}
			AnonymousClassDeclaration decl= node.getAnonymousClassDeclaration();
			if (decl != null) {
				decl.accept(this);
			}
		}
		return false;
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.EnumDeclaration)
	 */
	public boolean visit(EnumDeclaration node) {
		if (visit(node, false)) {
			List enumConstants= node.enumConstants();
			for (Iterator iter = enumConstants.iterator(); iter.hasNext();) {
				((EnumConstantDeclaration) iter.next()).accept(this);
			}
			List bodyDeclaration= node.bodyDeclarations();
			for (Iterator iter= bodyDeclaration.iterator(); iter.hasNext();) {
				((BodyDeclaration)iter.next()).accept(this);
			}
		}
		return false;
	}
	
	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.ExpressionStatement)
	 */
	public boolean visit(ExpressionStatement node) {
		return visit(node, false);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.FieldAccess)
	 */
	public boolean visit(FieldAccess node) {
		return visit(node, false);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.FieldDeclaration)
	 */
	public boolean visit(FieldDeclaration node) {
		if (visit(node, false)) {
			if (fBestMatch) {
				// check if the line contains a single field declaration.
				List fragments = node.fragments();
				if (fragments.size() == 1) {
					int offset= ((VariableDeclarationFragment)fragments.get(0)).getName().getStartPosition();
					// check if the breakpoint is to be set on the line which contains the name of the field
					if (lineNumber(offset) == fLineNumber) {
						fMemberOffset= offset;
						fLocationType= LOCATION_FIELD;
						fLocationFound= true;
						return false;
					}
				}
			}
			// visit only the variable declaration fragments, no the variable names.
			List fragments= node.fragments();
			for (Iterator iter= fragments.iterator(); iter.hasNext();) {
				((VariableDeclarationFragment)iter.next()).accept(this);
			}
		}
		return false;
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.ForStatement)
	 */
	public boolean visit(ForStatement node) {
		// in case on a "for(;;)", the breakpoint can be set on the first token of the node.
		return visit(node, node.initializers().isEmpty() && node.getExpression() == null && node.updaters().isEmpty());
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.IfStatement)
	 */
	public boolean visit(IfStatement node) {
		return visit(node, false);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.ImportDeclaration)
	 */
	public boolean visit(ImportDeclaration node) {
		return false;
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.InfixExpression)
	 */
	public boolean visit(InfixExpression node) {
		// if the breakpoint is to be set on a constant operand, the breakpoint needs to be
		// set on the first constant operand after the previous non-constant operand
		// (or the beginning of the expression, if there is no non-constant operand before).
		// ex:   foo() +    // previous non-constant operand
		//       1 +        // breakpoint set here
		//       2          // breakpoint asked to be set here
		if (visit(node, false)) {
			Expression leftOperand= node.getLeftOperand();
			Expression firstConstant= null;
			if (visit(leftOperand, false)) {
				leftOperand.accept(this);
				return false;
			} 
			if (isReplacedByConstantValue(leftOperand)) {
				firstConstant= leftOperand;
			}
			Expression rightOperand= node.getRightOperand();
			if (visit(rightOperand, false)) {
				if (firstConstant == null || !isReplacedByConstantValue(rightOperand)) {
					rightOperand.accept(this);
					return false;
				}
			} else {
				if (isReplacedByConstantValue(rightOperand)) {
					if (firstConstant == null) {
						firstConstant= rightOperand;
					}
				} else {
					firstConstant= null;
				}
				List extendedOperands= node.extendedOperands();
				for (Iterator iter= extendedOperands.iterator(); iter.hasNext();) {
					Expression operand= (Expression) iter.next();
					if (visit(operand, false)) {
						if (firstConstant == null || !isReplacedByConstantValue(operand)) {
							operand.accept(this);
							return false;
						}
						break;
					} 
					if (isReplacedByConstantValue(operand)) {
						if (firstConstant == null) {
							firstConstant= operand;
						}
					} else {
						firstConstant= null;
					}
					
				}
			}
			if(firstConstant != null){
				fLineLocation= lineNumber(firstConstant.getStartPosition());
				fLocationFound= true;
				fLocationType= LOCATION_LINE;
				fTypeName= computeTypeName(firstConstant);
			}
		}
		return false;
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.Initializer)
	 */
	public boolean visit(Initializer node) {
		return visit(node, false);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.InstanceofExpression)
	 */
	public boolean visit(InstanceofExpression node) {
		return visit(node, true);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.Javadoc)
	 */
	public boolean visit(Javadoc node) {
		return false;
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.LabeledStatement)
	 */
	public boolean visit(LabeledStatement node) {
        nestLabel(node.getLabel().getFullyQualifiedName());
		return visit(node, false);
	}
    
	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#endVisit(org.eclipse.jdt.core.dom.LabeledStatement)
	 */
	public void endVisit(LabeledStatement node) {
        popLabel();
        super.endVisit(node);
    }
    
    private String getLabel() {
        if (fLabels == null || fLabels.isEmpty()) {
            return null;
        }
        return (String) fLabels.get(fLabels.size() - 1);
    }
    
    private void nestLabel(String label) {
        if (fLabels == null) {
            fLabels = new ArrayList();
        }
        fLabels.add(label);
    }
    
    private void popLabel() {
        if (fLabels == null || fLabels.isEmpty()) {
            return;
        }
        fLabels.remove(fLabels.size() - 1);
    }

    /* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.LineComment)
	 */
	public boolean visit(LineComment node) {
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.MarkerAnnotation)
	 */
	public boolean visit(MarkerAnnotation node) {
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.MemberRef)
	 */
	public boolean visit(MemberRef node) {
		return false;
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.MemberValuePair)
	 */
	public boolean visit(MemberValuePair node) {
		return false;
	}
	
	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.MethodDeclaration)
	 */
	public boolean visit(MethodDeclaration node) {
		if (visit(node, false)) {
			if (fBestMatch) {
				// check if we are on the line which contains the method name
				int nameOffset= node.getName().getStartPosition();
				if (lineNumber(nameOffset) == fLineNumber) {
					fMemberOffset= nameOffset;
					fLocationType= LOCATION_METHOD;
					fLocationFound= true;
					return false;
				}
			}
			// visit only the body
			Block body = node.getBody();
			if (body != null) { // body is null for abstract methods
				body.accept(this);
			}
		}
		return false;
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.MethodInvocation)
	 */
	public boolean visit(MethodInvocation node) {
		return visit(node, true);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.MethodRef)
	 */
	public boolean visit(MethodRef node) {
		return false;
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.MethodRefParameter)
	 */
	public boolean visit(MethodRefParameter node) {
		return false;
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.Modifier)
	 */
	public boolean visit(Modifier node) {
		return false;
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.NormalAnnotation)
	 */
	public boolean visit(NormalAnnotation node) {
		return false;
	}
	
	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.NullLiteral)
	 */
	public boolean visit(NullLiteral node) {
		return visit(node, true);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.NumberLiteral)
	 */
	public boolean visit(NumberLiteral node) {
		return visit(node, true);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.PackageDeclaration)
	 */
	public boolean visit(PackageDeclaration node) {
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.ParameterizedType)
	 */
	public boolean visit(ParameterizedType node) {
		return false;
	}
	
	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.ParenthesizedExpression)
	 */
	public boolean visit(ParenthesizedExpression node) {
		return visit(node, false);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.PostfixExpression)
	 */
	public boolean visit(PostfixExpression node) {
		return visit(node, true);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.PrefixExpression)
	 */
	public boolean visit(PrefixExpression node) {
		if (visit(node, false)) {
			if (isReplacedByConstantValue(node)) {
				fLineLocation= lineNumber(node.getStartPosition());
				fLocationFound= true;
				fLocationType= LOCATION_LINE;
				fTypeName= computeTypeName(node);
				return false;
			}
			return true;
		}
		return false;
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.PrimitiveType)
	 */
	public boolean visit(PrimitiveType node) {
		return false;
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.QualifiedName)
	 */
	public boolean visit(QualifiedName node) {
		visit(node, true);
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.QualifiedType)
	 */
	public boolean visit(QualifiedType node) {
		return false;
	}
	
	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.ReturnStatement)
	 */
	public boolean visit(ReturnStatement node) {
		return visit(node, true);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.SimpleName)
	 */
	public boolean visit(SimpleName node) {
        // the name is only code if its not the current label (if any)
		return visit(node, !node.getFullyQualifiedName().equals(getLabel()));
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.SimpleType)
	 */
	public boolean visit(SimpleType node) {
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.SingleMemberAnnotation)
	 */
	public boolean visit(SingleMemberAnnotation node) {
		return false;
	}
	
	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.SingleVariableDeclaration)
	 */
	public boolean visit(SingleVariableDeclaration node) {
		return visit(node, false);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.StringLiteral)
	 */
	public boolean visit(StringLiteral node) {
		return visit(node, true);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.SuperConstructorInvocation)
	 */
	public boolean visit(SuperConstructorInvocation node) {
		return visit(node, true);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.SuperFieldAccess)
	 */
	public boolean visit(SuperFieldAccess node) {
		return visit(node, true);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.SuperMethodInvocation)
	 */
	public boolean visit(SuperMethodInvocation node) {
		return visit(node, true);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.SwitchCase)
	 */
	public boolean visit(SwitchCase node) {
		return false;
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.SwitchStatement)
	 */
	public boolean visit(SwitchStatement node) {
		return visit(node, false);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.SynchronizedStatement)
	 */
	public boolean visit(SynchronizedStatement node) {
		return visit(node, false);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.TagElement)
	 */
	public boolean visit(TagElement node) {
		return false;
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.TextElement)
	 */
	public boolean visit(TextElement node) {
		return false;
	}
	
	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.ThisExpression)
	 */
	public boolean visit(ThisExpression node) {
		return visit(node, true);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.ThrowStatement)
	 */
	public boolean visit(ThrowStatement node) {
		return visit(node, true);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.TryStatement)
	 */
	public boolean visit(TryStatement node) {
		return visit(node, false);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.TypeDeclaration)
	 */
	public boolean visit(TypeDeclaration node) {
		if (visit(node, false)) {
			// visit only the elements of the type declaration
			List bodyDeclaration= node.bodyDeclarations();
			for (Iterator iter= bodyDeclaration.iterator(); iter.hasNext();) {
				((BodyDeclaration)iter.next()).accept(this);
			}
		}
		return false;
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.TypeDeclarationStatement)
	 */
	public boolean visit(TypeDeclarationStatement node) {
		return visit(node, false);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.TypeParameter)
	 */
	public boolean visit(TypeParameter node) {
		return false;
	}
	
	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.TypeLiteral)
	 */
	public boolean visit(TypeLiteral node) {
		return false;
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.VariableDeclarationExpression)
	 */
	public boolean visit(VariableDeclarationExpression node) {
		return visit(node, false);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.VariableDeclarationFragment)
	 */
	public boolean visit(VariableDeclarationFragment node) {
		Expression initializer = node.getInitializer();
		if (visit(node, false)) {
			int startLine = lineNumber(node.getName().getStartPosition());
			if(initializer != null) {
				if (fLineNumber == startLine) {
					fLineLocation = startLine;
					fLocationFound = true;
					fLocationType = LOCATION_LINE;
					fTypeName = computeTypeName(node);
					return false;
				}
				initializer.accept(this);
			}
			else {
				//the variable has no initializer
				int offset = node.getName().getStartPosition();
				// check if the breakpoint is to be set on the line which contains the name of the field
				if (lineNumber(offset) == fLineNumber) {
					fMemberOffset = offset;
					fLocationType = LOCATION_FIELD;
					fLocationFound = true;
					return false;
				}
			}
		}
		return false;
	}
    
    private int lineNumber(int offset) {
        int lineNumber = fCompilationUnit.getLineNumber(offset);
        return lineNumber < 1 ? 1 : lineNumber;
    }
        

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.WildcardType)
	 */
	public boolean visit(WildcardType node) {
		return false;
	}
	
	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.VariableDeclarationStatement)
	 */
	public boolean visit(VariableDeclarationStatement node) {
		return visit(node, false);
	}

	/**
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(org.eclipse.jdt.core.dom.WhileStatement)
	 */
	public boolean visit(WhileStatement node) {
		return visit(node, false);
	}

}
