/*******************************************************************************
 * Copyright (c) 2000, 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.core.variables;

/**
 * A variable with a value that can be set and retrieved. The context in which
 * a value variable is referenced does not effect the value of the variable.
 * A value variable can be contributed by an extension or created programmatically.
 * A contributor may optionally specify an initial value for a variable, or
 * provide a delegate that will initialize the variable with a value.
 * <p>
 * Since 3.3, a variable can be specified as a "read only" preventing users from changing
 * the value after it has been initialized.  Furthermore, a read only variable that is
 * contributed by an extension will always load the value from the extension.
 * </p>
 * <p>
 * Example of a value variable contribution with an initial value, the specified
 * variable is created with the initial value "/usr/local/foo".
 * <pre>
 *  &lt;extension point="org.eclipse.core.variables.valueVariables"&gt;
 *   &lt;variable
 *    name="FOO_HOME"
 *    initialValue="/usr/local/foo"&gt;
 *   &lt;/variable&gt;
 *  &lt;/extension&gt;
 * </pre>
 * </p>
 * <p>
 * Example of a value variable contribution with an initializer class, the class
 * "com.example.FooLocator" will be used to initialize the value the first time
 * it's requested.
 * <pre>
 *  &lt;extension point="org.eclipse.core.variables.valueVariables"&gt;
 *   &lt;variable
 *    name="FOO_HOME"
 *    initializerClass="com.example.FooLocator"&gt;
 *   &lt;/variable&gt;
 *  &lt;/extension&gt;
 * </pre>
 * </p>
 * @since 3.0
 * @noimplement This interface is not intended to be implemented by clients.
 */
public interface IValueVariable extends IStringVariable {

	/**
	 * Sets the value of this variable to the given value.
	 * Since 3.3, this has no effect if this variable is read only.
	 *
	 * @param value variable value
	 */
	public void setValue(String value);
	
	/**
	 * Returns the value of this variable, or <code>null</code> if none.
	 * 
	 * @return the value of this variable, or <code>null</code> if none
	 */
	public String getValue();
	
	/**
	 * Returns whether this variable was contributed by an extension.
	 * 
	 * @return whether this variable was contributed by an extension
	 */
	public boolean isContributed();
	
	/**
	 * Returns whether this variable is read only.
	 * 
	 * @return whether this variable is read only
	 * @since 3.3
	 */
	public boolean isReadOnly();
	
	/**
	 * Sets the description of this variable to the given value.
	 * 
	 * @param description variable description, possibly <code>null</code>
	 */
	public void setDescription(String description);	
	
}
