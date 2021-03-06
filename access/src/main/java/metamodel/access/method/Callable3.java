/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2014 Michael Kroll
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package metamodel.access.method;

import java.lang.reflect.InvocationTargetException;

import metamodel.method.Method3;

/**
 * Call-wrapper for Method with 3 parameters.
 *
 * @author Michael Kroll
 * @param <BASE> type of class that declares the method
 * @param <RT> return type
 * @param <P1> type of first parameter
 * @param <P2> type of second parameter
 * @param <P3> type of third parameter
 */
public class Callable3<BASE, RT, P1, P2, P3> {

	private final BASE object;
	private final Method3<? super BASE, RT, P1, P2, P3> methodDefinition;

	public Callable3(
	        final BASE object,
	        final Method3<? super BASE, RT, P1, P2, P3> methodDefinition) {
		this.object = object;
		this.methodDefinition = methodDefinition;
	}

	/**
	 * Invoke Method with given parameters.
	 *
	 * @param param1 first parmeter
	 * @param param2 second parameter
	 * @param param3 third parameter
	 * @return result of method invocation. void-methods always return null
	 * @throws NoSuchMethodException
	 * @throws SecurityException
	 * @throws IllegalAccessException
	 * @throws IllegalArgumentException
	 * @throws InvocationTargetException
	 */
	public RT invoke(final P1 param1, final P2 param2, final P3 param3)
	        throws NoSuchMethodException, SecurityException, IllegalAccessException, IllegalArgumentException,
	        InvocationTargetException {
		return CallableHelper.invoke(object, methodDefinition, param1, param2, param3);
	}
}
