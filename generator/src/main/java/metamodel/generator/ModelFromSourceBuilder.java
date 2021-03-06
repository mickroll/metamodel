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
package metamodel.generator;

import japa.parser.JavaParser;
import japa.parser.ParseException;
import japa.parser.ast.CompilationUnit;
import japa.parser.ast.ImportDeclaration;
import japa.parser.ast.Node;
import japa.parser.ast.body.BodyDeclaration;
import japa.parser.ast.body.ClassOrInterfaceDeclaration;
import japa.parser.ast.body.ConstructorDeclaration;
import japa.parser.ast.body.EnumDeclaration;
import japa.parser.ast.body.FieldDeclaration;
import japa.parser.ast.body.MethodDeclaration;
import japa.parser.ast.body.Parameter;
import japa.parser.ast.body.TypeDeclaration;
import japa.parser.ast.body.VariableDeclarator;
import japa.parser.ast.comments.Comment;
import japa.parser.ast.type.ClassOrInterfaceType;
import japa.parser.ast.type.PrimitiveType;
import japa.parser.ast.type.PrimitiveType.Primitive;
import japa.parser.ast.type.ReferenceType;
import japa.parser.ast.type.Type;
import japa.parser.ast.type.VoidType;
import japa.parser.ast.type.WildcardType;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.Generated;

import metamodel.constructor.Constructor0;
import metamodel.constructor.impl.Constructor0Impl;
import metamodel.field.ArrayField;
import metamodel.field.SingularField;
import metamodel.field.impl.ArrayFieldImpl;
import metamodel.field.impl.SingularFieldImpl;
import metamodel.generator.converter.CollectionConverter;
import metamodel.generator.converter.FieldConverter;
import metamodel.generator.converter.FieldConverter.FieldDefinition;
import metamodel.generator.converter.MapConverter;
import metamodel.generator.converter.ObjectConverter;
import metamodel.method.Method0;
import metamodel.method.impl.Method0Impl;

import com.sun.codemodel.ClassType;
import com.sun.codemodel.JClass;
import com.sun.codemodel.JClassAlreadyExistsException;
import com.sun.codemodel.JCodeModel;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JDocComment;
import com.sun.codemodel.JExpr;
import com.sun.codemodel.JFieldVar;
import com.sun.codemodel.JInvocation;
import com.sun.codemodel.JMod;
import com.sun.codemodel.JType;

/**
 * Builds meta model from source files without compilation.
 *
 * @author Michael Kroll
 */
public class ModelFromSourceBuilder {

	/** List of all FieldConverters. Order is important, first converter with matching class wins. */
	private static final List<FieldConverter> FIELDCONVERTERS = Arrays.asList(
	        new CollectionConverter(),
	        new MapConverter(),
	        // ObjectConverter has to be last one
	        new ObjectConverter()
	        );

	/**
	 * Build metamodel for classes in source files.
	 *
	 * @param sourceFiles source files whose metamodel should be built.
	 * @return metamodel
	 */
	public JCodeModel buildCodeModel(final Set<File> sourceFiles) {
		final Map<String, JDefinedClass> definedClasses = new HashMap<>();
		final Map<JDefinedClass, String> classesToExtend = new HashMap<>();
		final JCodeModel codeModel = new JCodeModel();
		// 1. build code model for all classes, excluding "extends"-definitions (they are added in a later step)
		for (final File sourceFile : sourceFiles) {
			// parse the file
			final CompilationUnit cu;
			try (FileInputStream in = new FileInputStream(sourceFile)) {
				cu = JavaParser.parse(in);
			} catch (final IOException | ParseException | RuntimeException e) {
				System.err.println("unable to read source file " + sourceFile + ": " + e.getMessage());
				continue;
			}
			// write file
			try {
				for (final TypeDeclaration type : nullSafe(cu.getTypes())) {
					if (type instanceof ClassOrInterfaceDeclaration
					        || type instanceof EnumDeclaration) {
						final JDefinedClass classCodeModel =
						        codeModel._class(JMod.PUBLIC | JMod.ABSTRACT, getFullMetaModelClassName(cu, type),
						                ClassType.CLASS);

						final JClass baseType = findType(codeModel, cu, type.getName());
						defineClass(codeModel, classCodeModel, baseType, cu, type, definedClasses, classesToExtend);
					}
				}
			} catch (final JClassAlreadyExistsException | RuntimeException e) {
				System.err.println("unable to write meta class file for source file " + sourceFile + ": "
				        + e.getMessage());
			}
		}

		// 2. run through all classes that were defined and add "extends" where possible/needed
		for (final Entry<JDefinedClass, String> entry : classesToExtend.entrySet()) {
			final JDefinedClass classCodeModel = entry.getKey();
			final String wantedSuperClassName = entry.getValue();
			final List<JDefinedClass> candidates = findCandidates(definedClasses, wantedSuperClassName);
			if (candidates.isEmpty()) {
				/**
				 * this is likely not an error, because a source may extend a class from another module.
				 * <p>
				 * TODO add support for inter-module-class-hierarchies by trying to load wantedSuperClass. if this
				 * succeeds, add 'extends'-clause.
				 */
				final JDocComment javadoc = classCodeModel.javadoc();
				javadoc.add("Class " + classCodeModel.fullName() + " has superclass " + wantedSuperClassName
				        + " which is not included in generation context.");
			} else if (candidates.size() == 1) {
				classCodeModel._extends(candidates.get(0));
			} else {
				// arghh, more than one candidate :(
				// well, last try: search for candidate with same package name as subclass
				final List<JDefinedClass> candidatesInSamePackage = new ArrayList<>();
				for (final JDefinedClass candidate : candidates) {
					if (classCodeModel.getPackage().name().equals(candidate.getPackage().name())) {
						candidatesInSamePackage.add(candidate);
					}
				}
				if (candidatesInSamePackage.size() == 1) {
					// yay
					classCodeModel._extends(candidatesInSamePackage.get(0));
				} else {
					System.out.println("Class " + classCodeModel.fullName() + " has superclass "
					        + wantedSuperClassName + " which was found more than once in generation context.");
					final JDocComment javadoc = classCodeModel.javadoc();
					javadoc.add("Class " + classCodeModel.fullName() + " has superclass " + wantedSuperClassName
					        + " which was found more than once in generation context.");
				}
			}
		}

		return codeModel;
	}

	private List<JDefinedClass> findCandidates(final Map<String, JDefinedClass> definedClasses,
	        final String simpleClassName) {
		final List<JDefinedClass> result = new ArrayList<>();
		for (final JDefinedClass definedClass : definedClasses.values()) {
			if (simpleClassName.equals(definedClass.name())
			        || simpleClassName.equals(definedClass.fullName())) {
				result.add(definedClass);
			}
		}
		return result;
	}

	private String getFullMetaModelClassName(final CompilationUnit cu, final TypeDeclaration type) {
		if (cu.getPackage() != null) {
			final String packageName = cu.getPackage().getName().toString();
			return packageName + "." + getMetaModelClassName(type);
		} else {
			return getMetaModelClassName(type);
		}
	}

	private String getMetaModelClassName(final TypeDeclaration type) {
		return type.getName() + "_";
	}

	private String getMetaModelClassName(final String className) {
		return className + "_";
	}

	private String getFullClassName(final Node type) {
		String result;
		if (type instanceof ClassOrInterfaceType) {
			result = ((ClassOrInterfaceType) type).getName();
		} else if (type instanceof TypeDeclaration) {
			result = ((TypeDeclaration) type).getName();
		} else {
			throw new IllegalArgumentException("unknown starting type " + type.getClass().getName()
			        + " found while building full type name for " + type);
		}
		Node parent = type.getParentNode();
		while (parent != null) {
			if (parent instanceof ClassOrInterfaceType) {
				result = ((ClassOrInterfaceType) parent).getName() + "." + result;
			} else if (parent instanceof TypeDeclaration) {
				result = ((TypeDeclaration) parent).getName() + "." + result;
			} else if (parent instanceof CompilationUnit) {
				if (((CompilationUnit) parent).getPackage() == null) {
					return result;
				}
				result = ((CompilationUnit) parent).getPackage().getName().toString() + "." + result;
				return result;
			} else {
				throw new IllegalArgumentException("unknown type found while building full type name for " + type
				        + ": " + parent);
			}
			parent = parent.getParentNode();
		}
		return result;
	}

	private void defineClass(final JCodeModel codeModel, final JDefinedClass classCodeModel, final JClass baseType,
	        final CompilationUnit cu, final TypeDeclaration classType, final Map<String, JDefinedClass> definedClasses,
	        final Map<JDefinedClass, String> classesToExtend) {

		if (classType instanceof ClassOrInterfaceDeclaration) {
			final ClassOrInterfaceDeclaration clazz = (ClassOrInterfaceDeclaration) classType;
			if (clazz.getExtends() != null) {
				final String resolvedTypeName = resolveTypeName(cu, clazz.getExtends().get(0).getName());
				classesToExtend.put(classCodeModel, getMetaModelClassName(resolvedTypeName));
			}
		}

		classCodeModel.javadoc().add("@see " + classType.getName() + "\n");
		classCodeModel.annotate(Generated.class)
		        .param("value", this.getClass().getName())
		        .param("date", new Date().toString());

		// first generate fields for fields, so field names are equal to those in original class
		for (final BodyDeclaration member : nullSafe(classType.getMembers())) {
			if (member instanceof FieldDeclaration) {
				final FieldDeclaration field = (FieldDeclaration) member;
				if (Modifier.isStatic(field.getModifiers())) {
					continue;
				}
				addField(codeModel, classCodeModel, baseType, cu, classType, field);
			}
		}
		if (classType instanceof ClassOrInterfaceDeclaration) {
			// now generate fields for constructors, with automatic suffix for already present fields
			for (final BodyDeclaration member : nullSafe(classType.getMembers())) {
				if (member instanceof ConstructorDeclaration) {
					final ConstructorDeclaration constructor = (ConstructorDeclaration) member;
					addConstructor(codeModel, classCodeModel, baseType, cu, classType, constructor);
				}
			}
		}
		// now generate fields for methods, with automatic suffix for already present fields
		for (final BodyDeclaration member : nullSafe(classType.getMembers())) {
			if (member instanceof MethodDeclaration) {
				final MethodDeclaration method = (MethodDeclaration) member;
				if (Modifier.isStatic(method.getModifiers())) {
					continue;
				}
				addMethod(codeModel, classCodeModel, baseType, cu, classType, method);
			}
		}

		for (final BodyDeclaration member : nullSafe(classType.getMembers())) {
			if (member instanceof TypeDeclaration) {
				try {
					final TypeDeclaration innerType = (TypeDeclaration) member;
					// inner classes need to be static for inheritance to work without defining constructors
					final JDefinedClass innerClassModel =
					        classCodeModel._class(JMod.PUBLIC | JMod.STATIC | JMod.ABSTRACT,
					                getMetaModelClassName(innerType), ClassType.CLASS);
					// build full class name of inner class, because metamodel needs to import it for base of
					// members
					final String fullInnerClassName = getFullClassName(innerType);
					final JClass innerBaseType = codeModel.ref(fullInnerClassName);
					defineClass(codeModel, innerClassModel, innerBaseType, cu, innerType, definedClasses,
					        classesToExtend);
				} catch (final JClassAlreadyExistsException e) {
					throw new RuntimeException(e);
				}
			}
		}

		definedClasses.put(classCodeModel.fullName(), classCodeModel);
	}

	/**
	 * Add field-definition to metamodel.
	 *
	 * @param codeModel JCodeModel
	 * @param classCodeModel class-definition to fill
	 * @param cu
	 * @param classType
	 * @param field real-world-field
	 */
	private void addField(final JCodeModel codeModel, final JDefinedClass classCodeModel, final JClass baseType,
	        final CompilationUnit cu, final TypeDeclaration classType, final FieldDeclaration field) {
		final Type fieldType = field.getType();
		final JClass convertedType = convertType(codeModel, cu, fieldType, true);

		for (final VariableDeclarator variable : nullSafe(field.getVariables())) {
			final JClass fieldClazz;
			final JInvocation fieldInit;
			if (convertedType.isPrimitive()) {
				final JClass rawLLclazz = codeModel.ref(SingularField.class);
				fieldClazz = rawLLclazz.narrow(baseType, convertedType);
				fieldInit = JExpr._new(codeModel.ref(SingularFieldImpl.class).narrow(FieldConverter.DIAMOND))
				        .arg(variable.getId().getName()).arg(baseType.dotclass());
			} else if (convertedType.isArray()) {
				final JClass rawLLclazz = codeModel.ref(ArrayField.class);
				fieldClazz = rawLLclazz.narrow(baseType, convertedType);
				fieldInit = JExpr._new(codeModel.ref(ArrayFieldImpl.class).narrow(FieldConverter.DIAMOND))
				        .arg(variable.getId().getName()).arg(baseType.dotclass());
			} else {
				final FieldConverter fieldConverter = findFieldCoverter(codeModel, convertedType);
				if (fieldConverter == null) {
					// should not happen if ObjectConverter is present
					System.err.println("cannot convert " + baseType.fullName() + "#" + variable.getId().getName()
					        + " : no converter present for type " + convertedType.fullName());
					continue;
				}
				final FieldDefinition convertedField = fieldConverter.convert(codeModel, baseType, convertedType,
				        variable.getId().getName());
				fieldClazz = convertedField.getFieldClass();
				fieldInit = convertedField.getFieldInit();
			}

			final JFieldVar f = classCodeModel.field(JMod.PUBLIC | JMod.STATIC | JMod.FINAL, fieldClazz,
			        variable.getId().getName());
			f.init(fieldInit);
			if (field.getComment() != null) {
				f.javadoc().add(extractOriginalJavadoc(field.getComment()));
				f.javadoc().add("\n\n");
			}
			f.javadoc().add("@see " + classType.getName() + "#" + variable.getId().getName());
		}
	}

	/**
	 * Add constructor-definition to metamodel.
	 *
	 * @param codeModel JCodeModel
	 * @param classCodeModel class-definition to fill
	 * @param cu
	 * @param classType
	 * @param constructor real-world-constructor
	 */
	private void addConstructor(final JCodeModel codeModel, final JDefinedClass classCodeModel, final JClass baseType,
	        final CompilationUnit cu, final TypeDeclaration classType, final ConstructorDeclaration constructor) {

		final Collection<Parameter> parameters = nullSafe(constructor.getParameters());
		final List<JClass> typeArguments = new ArrayList<>();

		final String ctorDefinitionName = Constructor0.class.getName().replace("0", String.valueOf(parameters.size()));
		final String ctorDefinitionImplName = Constructor0Impl.class.getName().replace("0",
		        String.valueOf(parameters.size()));

		typeArguments.add(baseType);

		for (final Parameter parameter : parameters) {
			final JClass convertedParameterType = convertType(codeModel, cu, parameter.getType(), true);
			typeArguments.add(convertedParameterType);
		}

		final String uniqueFieldName = getUniqueFieldname(classCodeModel, "constructor", parameters);
		final JFieldVar f = classCodeModel.field(JMod.PUBLIC | JMod.STATIC | JMod.FINAL,
		        codeModel.ref(ctorDefinitionName).narrow(typeArguments), uniqueFieldName);
		final JInvocation fieldInit = JExpr
		        ._new(codeModel.ref(ctorDefinitionImplName).narrow(FieldConverter.DIAMOND))
		        .arg(baseType.dotclass());
		for (final Parameter parameter : parameters) {
			final JClass typeClass = getTypeClass(codeModel, cu, parameter.getType());
			fieldInit.arg(typeClass.dotclass());
		}
		f.init(fieldInit);
		f.javadoc().add("@see " + classType.getName() + "#" + getReferenceForJavadoc(classType.getName(), parameters));
	}

	/**
	 * Add method-definition to metamodel.
	 *
	 * @param codeModel JCodeModel
	 * @param classCodeModel class-definition to fill
	 * @param cu
	 * @param classType
	 * @param method real-world-method
	 */
	private void addMethod(final JCodeModel codeModel, final JDefinedClass classCodeModel, final JClass baseType,
	        final CompilationUnit cu, final TypeDeclaration classType, final MethodDeclaration method) {
		final Type returnType = method.getType();
		final JClass convertedReturnType = convertType(codeModel, cu, returnType, true);

		final Collection<Parameter> parameters = nullSafe(method.getParameters());
		final List<JClass> typeArguments = new ArrayList<>();

		final String methodDefinitionName = Method0.class.getName().replace("0", String.valueOf(parameters.size()));
		final String methodDefinitionImplName = Method0Impl.class.getName().replace("0",
		        String.valueOf(parameters.size()));

		typeArguments.add(baseType);
		typeArguments.add(convertedReturnType);

		for (final Parameter parameter : parameters) {
			final JClass convertedParameterType = convertType(codeModel, cu, parameter.getType(), true);
			typeArguments.add(convertedParameterType);
		}

		final String uniqueFieldName = getUniqueFieldname(classCodeModel, method.getName(), parameters);
		final JFieldVar f = classCodeModel.field(JMod.PUBLIC | JMod.STATIC | JMod.FINAL,
		        codeModel.ref(methodDefinitionName).narrow(typeArguments), uniqueFieldName);
		final JInvocation fieldInit = JExpr
		        ._new(codeModel.ref(methodDefinitionImplName).narrow(FieldConverter.DIAMOND))
		        .arg(method.getName()).arg(baseType.dotclass());
		for (final Parameter parameter : parameters) {
			final JClass typeClass = getTypeClass(codeModel, cu, parameter.getType());
			fieldInit.arg(typeClass.dotclass());
		}
		f.init(fieldInit);
		f.javadoc().add("@see " + classType.getName() + "#" + getReferenceForJavadoc(method.getName(), parameters));
	}

	/**
	 * Calculates a unique field name, starting from the original name of a method. Adds number of parameters and
	 * counter to field name if neccessary to obtain a unique field name.
	 *
	 * @param classCodeModel model of the defined class, containing already existent fields
	 * @param baseName base name of wanted field
	 * @param parameters parameters of method
	 * @return unique field name
	 */
	private String getUniqueFieldname(final JDefinedClass classCodeModel, final String baseName,
	        final Collection<Parameter> parameters) {
		// 1st try: method name
		if (classCodeModel.fields().get(baseName) == null) {
			return baseName;
		}
		// 2nd try: method name + _ + parameter count
		final String uniqueFieldNameWithParamCount = baseName + "_" + parameters.size();
		if (classCodeModel.fields().get(uniqueFieldNameWithParamCount) == null) {
			return uniqueFieldNameWithParamCount;
		}
		// 3rd try: method name + _ + parameter count + _ + counter
		String uniqueFieldNameWithParamCountAndCounter = uniqueFieldNameWithParamCount;
		int counter = 2;
		while (true) {
			uniqueFieldNameWithParamCountAndCounter = uniqueFieldNameWithParamCount + "_" + counter;
			if (classCodeModel.fields().get(uniqueFieldNameWithParamCountAndCounter) == null) {
				return uniqueFieldNameWithParamCountAndCounter;
			}
			counter++;
		}
	}

	/**
	 * Build method Javadoc reference in the form {@code methodName(ParamType1, ParamType2, ...)}
	 *
	 * @param methodName name of method
	 * @param parameters parameters of method
	 * @return JavaDoc reference to method declaration
	 */
	private String getReferenceForJavadoc(final String methodName, final Collection<Parameter> parameters) {
		final StringBuilder sb = new StringBuilder();
		sb.append(methodName);
		sb.append("(");
		boolean isFirst = true;
		for (final Parameter parameter : parameters) {
			if (isFirst) {
				isFirst = false;
			} else {
				sb.append(", ");
			}
			sb.append(parameter.getType().toString());
		}
		sb.append(")");
		return sb.toString();
	}

	/**
	 * Finds first matching FieldCoverter for given type.
	 *
	 * @param codeModel JCodeModel instance
	 * @param convertedType type to search a FieldConverter for
	 * @return FieldCoverter
	 */
	private FieldConverter findFieldCoverter(final JCodeModel codeModel, final JClass convertedType) {
		for (final FieldConverter fieldConverter : FIELDCONVERTERS) {
			if (codeModel.ref(fieldConverter.getTargetClass()).isAssignableFrom(convertedType.erasure())) {
				return fieldConverter;
			}
		}
		return null;
	}

	/**
	 * Extracts javadoc from a comment declaration.
	 *
	 * @param comment the comment
	 * @return the original javadoc
	 */
	private String extractOriginalJavadoc(final Comment comment) {
		final String originalContent = comment.getContent();
		// remove starting '* ' from every line
		final String processedContent = originalContent.replaceAll("\n[\t ]*\\*", "\n");
		return processedContent;
	}

	/**
	 * Tries to resolve a short type name to the fully qualified name.
	 *
	 * @param cu compilation unit that references the type
	 * @param typeName name of Type (may be shortened form, if import is present)
	 * @return fully qualified name, if resolving was successful, the short typeName otherwise
	 */
	private String resolveTypeName(final CompilationUnit cu, final String typeName) {
		for (final ImportDeclaration imp : nullSafe(cu.getImports())) {
			if (imp.getName().toString().endsWith("." + typeName)) {
				return imp.getName().toString();
			}
		}
		return typeName;
	}

	/**
	 * Find a type in the given CodeModel. Tries to resolve type by looking at imports of compilation unit.
	 *
	 * @param codeModel instance of JCodeModel
	 * @param cu CompilationUnit that references the Type
	 * @param typeName name of Type (may be shortened form, if import is present)
	 * @return corresponding type in code model
	 */
	private JClass findType(final JCodeModel codeModel, final CompilationUnit cu, final String typeName) {
		return codeModel.ref(resolveTypeName(cu, typeName));
	}

	private JClass getTypeClass(final JCodeModel codeModel, final CompilationUnit cu, final Type possibleGenericType) {
		if (possibleGenericType instanceof PrimitiveType) {
			// primitives are written as eg. int.class
			final Primitive primitive = ((PrimitiveType) possibleGenericType).getType();
			return codeModel.ref(primitive.name().toLowerCase());
		} else if (possibleGenericType instanceof ReferenceType) {
			final ReferenceType type = (ReferenceType) possibleGenericType;
			// boolean[], String[], Boolean[][][], Collection<String>[], ...
			JClass elementType = getTypeClass(codeModel, cu, type.getType());
			for (int i = 0; i < type.getArrayCount(); i++) {
				// add as much [] as needed
				elementType = elementType.array();
			}
			return elementType;
		} else if (possibleGenericType instanceof ClassOrInterfaceType) {
			final ClassOrInterfaceType type = (ClassOrInterfaceType) possibleGenericType;
			final JClass baseType = findType(codeModel, cu, type.getName());
			return baseType;
		}
		// should not get here
		throw new IllegalArgumentException("cannot determine class of " + possibleGenericType.toString());
	}

	/**
	 * Convert a {@link Type} to {@link JClass}. This includes generic type information.
	 *
	 * @param codeModel JCodeModel
	 * @param possibleGenericType type to convert
	 * @return converted type
	 */
	private JClass convertType(final JCodeModel codeModel, final CompilationUnit cu, final Type possibleGenericType,
	        final boolean boxify) {
		if (possibleGenericType instanceof VoidType) {
			return codeModel.VOID.boxify();
		} else if (possibleGenericType instanceof PrimitiveType) {
			// int, boolean, short, ...
			final PrimitiveType type = (PrimitiveType) possibleGenericType;
			if (boxify) {
				return JType.parse(codeModel, type.getType().toString().toLowerCase()).boxify();
			} else {
				return codeModel.ref(type.getType().toString().toLowerCase());
			}
		} else if (possibleGenericType instanceof ReferenceType) {
			final ReferenceType type = (ReferenceType) possibleGenericType;
			if (type.getArrayCount() == 0) {
				// String, Boolean, Collection<String>, ...
				return convertType(codeModel, cu, type.getType(), true);
			} else {
				// String[], Boolean[][][], Collection<String>[], ...
				JClass elementType = convertType(codeModel, cu, type.getType(), false);
				for (int i = 0; i < type.getArrayCount(); i++) {
					// add as much [] as needed
					elementType = elementType.array();
				}
				return elementType;
			}
		} else if (possibleGenericType instanceof WildcardType) {
			final WildcardType wildcardType = (WildcardType) possibleGenericType;
			final ReferenceType extend = wildcardType.getExtends();
			if (extend != null) {
				// List<? extends Something>, ...
				final JClass upperBound = convertType(codeModel, cu, extend, true);
				return upperBound.wildcard();
			}
			// List<?>, MyClass<?>, ...
			return codeModel.wildcard();
		} else if (possibleGenericType instanceof ClassOrInterfaceType) {
			final ClassOrInterfaceType type = (ClassOrInterfaceType) possibleGenericType;
			final JClass baseType = findType(codeModel, cu, type.getName());
			if (type.getTypeArgs() == null) {
				// String
				return baseType;
			} else {
				// List<String>, List<Map<?, ?>>, ...
				final JClass[] convertedTypeArgs = new JClass[type.getTypeArgs().size()];
				for (int i = 0; i < type.getTypeArgs().size(); i++) {
					final Type typeArg = type.getTypeArgs().get(i);
					convertedTypeArgs[i] = convertType(codeModel, cu, typeArg, true);
				}
				return baseType.narrow(convertedTypeArgs);
			}
		}
		// should not get here
		throw new IllegalArgumentException("cannot convert " + possibleGenericType.toString());
	}

	private <T> Collection<T> nullSafe(final Collection<T> collection) {
		if (collection != null) {
			return collection;
		}
		return Collections.<T> emptyList();
	}
}
