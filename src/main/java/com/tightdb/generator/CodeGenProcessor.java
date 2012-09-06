package com.tightdb.generator;

import java.io.File;
import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.StandardLocation;

import org.apache.commons.lang.StringUtils;

import com.tightdb.Table;

public class CodeGenProcessor extends AbstractAnnotationProcessor {

	private static final String SOURCE_FOLDERS = "source_folders";

	private static final String MSG_INCORRECT_TYPE = "Incorrect data type was specified! Expected primitive or wrapper type, byte[], java.lang.Object, java.util.Date or java.nio.ByteBuffer!";

	private static final String MSG_NO_COLUMNS = "The table specification must have at least one valid field/column specified!";

	private static final String DEFAULT_PACKAGE = "com.tightdb.generated";

	public static final String INFO_GENERATED = "/* This file was automatically generated by TightDB. */";

	private static final Set<String> NUM_TYPES;

	private final CodeRenderer renderer = new CodeRenderer();

	static {
		NUM_TYPES = new HashSet<String>(Arrays.asList("long", "int", "byte",
				"short", "java.lang.Long", "java.lang.Integer",
				"java.lang.Byte", "java.lang.Short"));
	}

	private Map<String, TypeElement> tables = new HashMap<String, TypeElement>();
	private Map<String, TypeElement> subtables = new HashMap<String, TypeElement>();
	private Map<String, ModelInfo> modelsInfo = new HashMap<String, ModelInfo>();
	private FieldSorter fieldSorter;

	@Override
	public void processAnnotations(Set<? extends TypeElement> annotations,
			RoundEnvironment env) throws Exception {
		fieldSorter = new FieldSorter(logger, getSourceFolders());

		for (TypeElement annotation : annotations) {
			String annotationName = annotation.getQualifiedName().toString();
			if (annotationName.equals(Table.class.getCanonicalName())) {
				Set<? extends Element> elements = env
						.getElementsAnnotatedWith(annotation);
				processAnnotatedElements(elements);
			} else {
				logger.warn("Unexpected annotation: " + annotationName);
			}
		}
	}

	private void processAnnotatedElements(Set<? extends Element> elements)
			throws IOException {
		logger.info("Processing " + elements.size() + " elements...");

		URI uri = filer.getResource(StandardLocation.SOURCE_OUTPUT, "", "foo")
				.toUri();
		if (uri.toString().equals("foo")) {
			throw new RuntimeException(
					"The path of the Java source and generated files must be configured as source output! (see -s option of javac)");
		}

		List<File> sourcesPath = new LinkedList<File>();

		// FIXME: Workaround for OS X 
		try {
			if (uri.getScheme() == null) uri = new URI("file", uri.getSchemeSpecificPart(), uri.getFragment());
		}
		catch (URISyntaxException e) {
			logger.error("Failed to add 'file:' schema to schema-less URI '"+uri+"'");
		}

		File file = new File(uri);
		File generatedSourcesPath = file.getParentFile();
		sourcesPath.add(generatedSourcesPath);

		String[] sourceFolders = getSourceFolders();
		while (generatedSourcesPath != null) {
			for (String sourceFolder : sourceFolders) {
				File potentialPath = new File(generatedSourcesPath, sourceFolder);
				if (potentialPath.exists()) {
					sourcesPath.add(potentialPath);
					logger.info("Configured source folder: " + potentialPath);
				}
			}
			generatedSourcesPath = generatedSourcesPath.getParentFile();
		}

		prepareTables(elements);

		for (Element element : elements) {
			if (element instanceof TypeElement) {
				TypeElement model = (TypeElement) element;
				setupModelInfo(model);
			}
		}

		for (Element element : elements) {
			if (element instanceof TypeElement) {
				TypeElement model = (TypeElement) element;
				processModel(sourcesPath, model);
			}
		}
	}

	private void setupModelInfo(TypeElement model) {
		AnnotationMirror annotationMirror = getAnnotationInfo(model,
				Table.class);
		String tableName = getAttribute(annotationMirror, "table");
		String cursorName = getAttribute(annotationMirror, "row");
		String viewName = getAttribute(annotationMirror, "view");
		String queryName = getAttribute(annotationMirror, "query");

		String entity = StringUtils
				.capitalize(model.getSimpleName().toString());

		tableName = tableName == null ? calculateTableName(entity) : tableName;
		cursorName = cursorName == null ? calculateCursorName(entity)
				: cursorName;
		viewName = viewName == null ? calculateViewName(entity) : viewName;
		queryName = queryName == null ? calculateQueryName(entity) : queryName;

		modelsInfo.put(entity, new ModelInfo(tableName, cursorName, viewName,
				queryName));
	}

	private void processModel(List<File> sourceFolders, TypeElement model) {
		String modelType = model.getQualifiedName().toString();

		List<VariableElement> fields = getFields(model);

		// sort the fields, due to unresolved bug in Eclipse APT
		fieldSorter.sortFields(fields, model, sourceFolders);

		// get the capitalized model name
		String entity = StringUtils
				.capitalize(model.getSimpleName().toString());

		logger.info("Generating code for entity '" + entity + "' with "
				+ fields.size() + " columns...");

		/*********** Prepare the attributes for the templates ****************/

		/* Construct the list of columns */

		final List<Model> columns = getColumns(fields);
		if (columns.isEmpty()) {
			logger.warn(MSG_NO_COLUMNS, model);
		}

		/* Set the attributes */

		String packageName = calculatePackageName(model);
		boolean isNested = isSubtable(modelType);

		ModelInfo modelInfo = modelsInfo.get(entity);

		Map<String, Object> commonAttr = new HashMap<String, Object>();
		commonAttr.put("columns", columns);
		commonAttr.put("isNested", isNested);
		commonAttr.put("packageName", packageName);
		commonAttr.put("tableName", modelInfo.getTableName());
		commonAttr.put("viewName", modelInfo.getViewName());
		commonAttr.put("cursorName", modelInfo.getCursorName());
		commonAttr.put("queryName", modelInfo.getQueryName());
		commonAttr.put("java_header", INFO_GENERATED);

		generateSources(model, modelInfo.getTableName(),
				modelInfo.getCursorName(), modelInfo.getViewName(),
				modelInfo.getQueryName(), packageName, commonAttr);
	}

	private List<Model> getColumns(List<VariableElement> fields) {
		int index = 0;
		final List<Model> columns = new ArrayList<Model>();
		for (VariableElement field : fields) {
			String columnType = getColumnType(field);
			if (columnType != null) {
				String originalType = fieldType(field);
				String fieldType = getAdjustedFieldType(field);
				String paramType = getParamType(field);
				String fieldName = field.getSimpleName().toString();

				boolean isSubtable = isSubtable(fieldType);
				String subtype = isSubtable ? getSubtableType(field) : null;

				Model column = new Model();
				column.put("name", fieldName);
				column.put("type", columnType);
				column.put("originalType", originalType);
				column.put("fieldType", fieldType);
				column.put("paramType", paramType);
				column.put("index", index++);
				column.put("isSubtable", isSubtable);

				if (isSubtable) {
					ModelInfo subModelInfo = modelsInfo.get(subtype);
					column.put("subTableName", subModelInfo.getTableName());
					column.put("subCursorName", subModelInfo.getCursorName());
					column.put("subViewName", subModelInfo.getViewName());
					column.put("subQueryName", subModelInfo.getQueryName());
				}

				columns.add(column);
			} else {
				logger.error(MSG_INCORRECT_TYPE, field);
			}
		}
		return columns;
	}

	private void generateSources(TypeElement model, String tableName,
			String cursorName, String viewName, String queryName,
			String packageName, Map<String, Object> commonAttr) {
		/*********** Generate the table class ****************/

		Model table = new Model();
		table.put("name", tableName);
		table.putAll(commonAttr);

		/* Generate the "add" method in the table class */

		Model methodAdd = new Model();
		methodAdd.putAll(commonAttr);
		table.put("add", renderer.render("table_add.ftl", methodAdd));

		/* Generate the "insert" method in the table class */

		Model methodInsert = new Model();
		methodInsert.putAll(commonAttr);
		table.put("insert", renderer.render("table_insert.ftl", methodInsert));

		/* Generate the table class */

		String tableContent = renderer.render("table.ftl", table);
		writeToSourceFile(packageName, tableName, tableContent, model);

		/*********** Generate the cursor class ****************/

		Model cursor = new Model();
		cursor.put("name", cursorName);
		cursor.putAll(commonAttr);

		String cursorContent = renderer.render("cursor.ftl", cursor);
		writeToSourceFile(packageName, cursorName, cursorContent, model);

		/*********** Generate the view class ****************/

		Model view = new Model();
		view.put("name", viewName);
		view.putAll(commonAttr);

		String viewContent = renderer.render("view.ftl", view);
		writeToSourceFile(packageName, viewName, viewContent, model);

		/*********** Generate the query class ****************/

		Model query = new Model();
		query.put("name", queryName);
		query.putAll(commonAttr);

		String queryContent = renderer.render("query.ftl", query);
		writeToSourceFile(packageName, queryName, queryContent, model);
	}

	private String calculateTableName(String entity) {
		return entity + "Table";
	}

	private String calculateViewName(String entity) {
		return entity + "View";
	}

	private String calculateQueryName(String entity) {
		return entity + "Query";
	}

	private String calculateCursorName(String entity) {
		return entity + "Row";
	}

	private String calculatePackageName(TypeElement model) {
		Element parent = model.getEnclosingElement();
		while (parent != null && !(parent instanceof PackageElement)) {
			parent = parent.getEnclosingElement();
		}

		if (parent instanceof PackageElement) {
			PackageElement pkg = (PackageElement) parent;
			String pkgName = pkg.getQualifiedName().toString();
			return pkgName.isEmpty() ? "" : pkgName;
		} else {
			logger.warn("Couldn't calculate the target package! Using default: "
					+ DEFAULT_PACKAGE);
			return DEFAULT_PACKAGE;
		}
	}

	private List<VariableElement> getFields(Element element) {
		List<VariableElement> fields = new ArrayList<VariableElement>();

		for (Element enclosedElement : element.getEnclosedElements()) {
			if (enclosedElement.getKind().equals(ElementKind.FIELD)) {
				if (enclosedElement instanceof VariableElement) {
					VariableElement field = (VariableElement) enclosedElement;
					fields.add(field);
				}
			}
		}

		return fields;
	}

	private void prepareTables(Set<? extends Element> elements) {
		for (Element element : elements) {
			if (element instanceof TypeElement) {
				TypeElement model = (TypeElement) element;
				String name = model.getQualifiedName().toString();
				if (isReferencedBy(model, elements)) {
					logger.info("Detected subtable: " + name);
					subtables.put(name, model);
				} else {
					logger.info("Detected top-level table: " + name);
					tables.put(name, model);
				}
			}
		}
	}

	private boolean isReferencedBy(TypeElement model,
			Set<? extends Element> elements) {
		String modelType = model.getQualifiedName().toString();

		for (Element element : elements) {
			for (Element enclosedElement : element.getEnclosedElements()) {
				if (enclosedElement.getKind().equals(ElementKind.FIELD)) {
					if (enclosedElement instanceof VariableElement) {
						VariableElement field = (VariableElement) enclosedElement;
						TypeMirror fieldType = field.asType();
						if (fieldType.getKind().equals(TypeKind.DECLARED)) {
							Element typeAsElement = typeUtils
									.asElement(fieldType);
							if (typeAsElement instanceof TypeElement) {
								TypeElement typeElement = (TypeElement) typeAsElement;
								if (typeElement.getQualifiedName().toString()
										.equals(modelType)) {
									return true;
								}
							}
						}
					}
				}
			}
		}

		return false;
	}

	private String getColumnType(VariableElement field) {
		String type = fieldType(field);

		String columnType;
		if (NUM_TYPES.contains(type)) {
			columnType = "Long";
		} else if ("boolean".equals(type) || "java.lang.Boolean".equals(type)) {
			columnType = "Boolean";
		} else if ("java.lang.String".equals(type)) {
			columnType = "String";
		} else if ("java.util.Date".equals(type)) {
			columnType = "Date";
		} else if ("byte[]".equals(type) || "java.nio.ByteBuffer".equals(type)) {
			columnType = "Binary";
		} else if (isSubtable(type)) {
			columnType = "Table";
		} else if ("java.lang.Object".equals(type)) {
			columnType = "Mixed";
		} else {
			columnType = null;
		}

		return columnType;
	}

	private boolean isSubtable(String type) {
		return subtables.containsKey(type);
	}

	private String fieldType(VariableElement field) {
		return field.asType().toString();
	}

	private String fieldSimpleType(VariableElement field) {
		return fieldType(field).replaceFirst("<.*>", "").replaceFirst(".*\\.",
				"");
	}

	private String getSubtableType(VariableElement field) {
		return StringUtils.capitalize(fieldSimpleType(field));
	}

	private String getAdjustedFieldType(VariableElement field) {
		String type = fieldType(field);

		if (NUM_TYPES.contains(type)) {
			type = "long";
		} else if (type.equals("byte[]")) {
			type = "java.nio.ByteBuffer";
		} else if (type.equals("java.lang.Object")) {
			type = "com.tightdb.Mixed";
		}

		return type;
	}

	private String getParamType(VariableElement field) {
		String type = fieldType(field);

		if (NUM_TYPES.contains(type)) {
			type = "long";
		} else if (type.equals("java.lang.Object")) {
			type = "com.tightdb.Mixed";
		}

		return type;
	}

	private static AnnotationMirror getAnnotationInfo(TypeElement typeElement,
			Class<?> clazz) {
		String clazzName = clazz.getName();
		for (AnnotationMirror m : typeElement.getAnnotationMirrors()) {
			if (m.getAnnotationType().toString().equals(clazzName)) {
				return m;
			}
		}
		return null;
	}

	private static String getAttribute(AnnotationMirror annotationMirror,
			String name) {
		for (Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : annotationMirror
				.getElementValues().entrySet()) {
			if (entry.getKey().getSimpleName().toString().equals(name)) {
				return String.valueOf(entry.getValue().getValue());
			}
		}
		return null;
	}

	private String[] getSourceFolders() {
		String sourceFolders = options.get(SOURCE_FOLDERS);
		if (sourceFolders != null) {
			return sourceFolders.split("[\\:\\,\\;]");
		} else {
			return new String[] { "src", "src/main/java", "src/test/java" };
		}
	}
}
