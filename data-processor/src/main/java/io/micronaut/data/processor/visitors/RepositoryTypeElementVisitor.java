/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.data.processor.visitors;

import io.micronaut.context.annotation.Parameter;
import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.AnnotationValueBuilder;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.expressions.EvaluatedExpressionReference;
import io.micronaut.core.io.service.SoftServiceLoader;
import io.micronaut.core.naming.NameUtils;
import io.micronaut.core.order.OrderUtil;
import io.micronaut.core.reflect.ClassUtils;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.core.util.StringUtils;
import io.micronaut.data.annotation.EntityRepresentation;
import io.micronaut.data.annotation.Join;
import io.micronaut.data.annotation.ParameterExpression;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.annotation.RepositoryConfiguration;
import io.micronaut.data.annotation.TypeRole;
import io.micronaut.data.annotation.sql.Procedure;
import io.micronaut.data.intercept.annotation.DataMethod;
import io.micronaut.data.intercept.annotation.DataMethodQuery;
import io.micronaut.data.intercept.annotation.DataMethodQueryParameter;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.DataType;
import io.micronaut.data.model.JsonDataType;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.model.PersistentProperty;
import io.micronaut.data.model.PersistentPropertyPath;
import io.micronaut.data.model.Slice;
import io.micronaut.data.model.Sort;
import io.micronaut.data.model.query.BindingParameter;
import io.micronaut.data.model.query.JoinPath;
import io.micronaut.data.model.query.builder.AdditionalParameterBinding;
import io.micronaut.data.model.query.builder.QueryBuilder;
import io.micronaut.data.model.query.builder.QueryParameterBinding;
import io.micronaut.data.model.query.builder.QueryResult;
import io.micronaut.data.model.query.builder.sql.SqlQueryBuilder;
import io.micronaut.data.processor.model.SourcePersistentEntity;
import io.micronaut.data.processor.model.SourcePersistentProperty;
import io.micronaut.data.processor.model.criteria.impl.SourceParameterExpressionImpl;
import io.micronaut.data.processor.visitors.finders.FindersUtils;
import io.micronaut.data.processor.visitors.finders.MethodMatchInfo;
import io.micronaut.data.processor.visitors.finders.MethodMatcher;
import io.micronaut.data.processor.visitors.finders.RawQueryMethodMatcher;
import io.micronaut.data.processor.visitors.finders.TypeUtils;
import io.micronaut.data.repository.GenericRepository;
import io.micronaut.inject.annotation.EvaluatedExpressionReferenceCounter;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.TypedElement;
import io.micronaut.inject.processing.ProcessingException;
import io.micronaut.inject.visitor.ElementPostponedToNextRoundException;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The main {@link TypeElementVisitor} that visits interfaces annotated with {@link Repository}
 * and generates queries for each abstract method.
 *
 * @author graemerocher
 * @since 1.0.0
 */
@Internal
public class RepositoryTypeElementVisitor implements TypeElementVisitor<Repository, Object> {

    public static final String SPRING_REPO = "org.springframework.data.repository.Repository";
    private static final boolean IS_DOCUMENT_ANNOTATION_PROCESSOR = ClassUtils.isPresent("io.micronaut.data.document.processor.mapper.MappedEntityMapper", RepositoryTypeElementVisitor.class.getClassLoader());

    private ClassElement currentClass;
    private ClassElement currentRepository;
    private QueryBuilder queryEncoder;
    private final Map<String, String> typeRoles = new HashMap<>();
    private final List<MethodMatcher> methodsMatchers;
    private boolean failing = false;
    private final Set<String> visitedRepositories = new HashSet<>();
    private Map<String, DataType> dataTypes = Collections.emptyMap();
    private final Map<String, SourcePersistentEntity> entityMap = new HashMap<>(50);
    private Function<ClassElement, SourcePersistentEntity> entityResolver;

    {
        List<MethodMatcher> matcherList = new ArrayList<>(20);
        SoftServiceLoader.load(MethodMatcher.class).collectAll(matcherList);
        OrderUtil.sort(matcherList);
        methodsMatchers = matcherList;
    }

    /**
     * Default constructor.
     */
    public RepositoryTypeElementVisitor() {
        typeRoles.put(Pageable.class.getName(), TypeRole.PAGEABLE);
        typeRoles.put(Sort.class.getName(), TypeRole.SORT);
        typeRoles.put(CursoredPage.class.getName(), TypeRole.CURSORED_PAGE);
        typeRoles.put(Page.class.getName(), TypeRole.PAGE);
        typeRoles.put(Slice.class.getName(), TypeRole.SLICE);
    }

    @NonNull
    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    private Map<ClassElement, FindInterceptorDef> createFindInterceptors(ClassElement element, VisitorContext visitorContext) {
        List<FindInterceptorDef> defaultInterceptors = FindersUtils.getDefaultInterceptors(visitorContext);
        List<FindInterceptorDef> interceptors = new ArrayList<>(defaultInterceptors);
        AnnotationValue<RepositoryConfiguration> repositoryConfiguration = element.getAnnotationMetadata()
            .getAnnotation(RepositoryConfiguration.class);
        if (repositoryConfiguration != null) {
            for (AnnotationValue<io.micronaut.data.annotation.FindInterceptorDef> interceptor : repositoryConfiguration
                .getAnnotations("findInterceptors", io.micronaut.data.annotation.FindInterceptorDef.class)) {
                interceptors.add(new FindInterceptorDef(
                    interceptor.stringValue("returnType").flatMap(visitorContext::getClassElement).orElseThrow(),
                    interceptor.booleanValue("isContainer").orElse(true),
                    interceptor.stringValue("interceptor").flatMap(visitorContext::getClassElement).orElseThrow()
                ));
            }
        }
        return interceptors.stream().collect(Collectors.toMap(FindInterceptorDef::returnType, e -> e));
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        String interfaceName = element.getName();
        if (failing) {
            return;
        }
        if (visitedRepositories.contains(interfaceName)) {
            // prevent duplicate visits
            currentRepository = null;
            currentClass = null;
            return;
        }
        if (element.hasStereotype("io.micronaut.data.document.annotation.DocumentProcessorRequired") && !IS_DOCUMENT_ANNOTATION_PROCESSOR) {
            context.fail("Repository is required to be processed by the data-document-processor. " +
                "Make sure it's included as a dependency to the annotation processor classpath!", element);
            failing = true;
            return;
        }

        this.currentClass = element;

        entityResolver = new Function<>() {

            final MappedEntityVisitor mappedEntityVisitor = new MappedEntityVisitor();
            final MappedEntityVisitor embeddedMappedEntityVisitor = new MappedEntityVisitor(false);

            @Override
            public SourcePersistentEntity apply(ClassElement classElement) {
                String classNameKey = getClassNameKey(classElement);
                return entityMap.computeIfAbsent(classNameKey, s -> {
                    if (classElement.hasAnnotation("io.micronaut.data.annotation.Embeddable")) {
                        embeddedMappedEntityVisitor.visitClass(classElement, context);
                    } else {
                        mappedEntityVisitor.visitClass(classElement, context);
                    }
                    return new SourcePersistentEntity(classElement, this);
                });
            }
        };

        if (element.hasDeclaredStereotype(Repository.class)) {
            visitedRepositories.add(interfaceName);
            currentRepository = element;
            queryEncoder = QueryBuilder.newQueryBuilder(element.getAnnotationMetadata());
            this.dataTypes = Utils.getConfiguredDataTypes(currentRepository);
            AnnotationMetadata annotationMetadata = element.getAnnotationMetadata();
            List<AnnotationValue<TypeRole>> roleArray = annotationMetadata
                .findAnnotation(RepositoryConfiguration.class)
                .map(av -> av.getAnnotations("typeRoles", TypeRole.class))
                .orElse(Collections.emptyList());
            for (AnnotationValue<TypeRole> parameterRole : roleArray) {
                String role = parameterRole.stringValue("role").orElse(null);
                AnnotationClassValue cv = parameterRole.get("type", AnnotationClassValue.class).orElse(null);
                if (StringUtils.isNotEmpty(role) && cv != null) {
                    context.getClassElement(cv.getName()).ifPresent(ce ->
                        typeRoles.put(ce.getName(), role)
                    );
                }
            }
            if (element.isAssignable(SPRING_REPO)) {
                context.getClassElement("org.springframework.data.domain.Pageable").ifPresent(ce ->
                    typeRoles.put(ce.getName(), TypeRole.PAGEABLE)
                );
                context.getClassElement("org.springframework.data.domain.Page").ifPresent(ce ->
                    typeRoles.put(ce.getName(), TypeRole.PAGE)
                );
                context.getClassElement("org.springframework.data.domain.Slice").ifPresent(ce ->
                    typeRoles.put(ce.getName(), TypeRole.SLICE)
                );
                context.getClassElement("org.springframework.data.domain.Sort").ifPresent(ce ->
                    typeRoles.put(ce.getName(), TypeRole.SORT)
                );
            }

            // Annotate repository with EntityRepresentation if present on entity class
            annotateEntityRepresentationIfPresent(element);

            if (queryEncoder == null) {
                context.fail("QueryEncoder not present on annotation processor path", element);
                failing = true;
            }
        }

    }

    @Override
    public void visitMethod(MethodElement element, VisitorContext context) {
        if (currentRepository == null || failing) {
            return;
        }
        ClassElement genericReturnType = element.getGenericReturnType();
        if (queryEncoder != null && currentClass != null && element.isAbstract() && !element.isStatic() && methodsMatchers != null) {
            ParameterElement[] parameters = element.getParameters();
            Map<String, Element> parametersInRole = new HashMap<>(2);
            for (ParameterElement parameter : parameters) {
                ClassElement type = parameter.getType();
                this.typeRoles.entrySet().stream().filter(entry -> {
                        String roleType = entry.getKey();
                        return type.isAssignable(roleType);
                    }
                ).forEach(entry ->
                    parametersInRole.put(entry.getValue(), parameter)
                );
            }

            if (element.hasDeclaredAnnotation(DataMethod.class)) {
                // explicitly handled
                return;
            }

            Map<ClassElement, FindInterceptorDef> findInterceptors = createFindInterceptors(currentClass, context);

            MatchContext matchContext = new MatchContext(
                queryEncoder,
                currentRepository,
                context,
                element,
                typeRoles,
                genericReturnType,
                parameters,
                findInterceptors);

            try {
                SourcePersistentEntity entity = resolvePersistentEntity(element, parametersInRole);
                MethodMatchContext methodMatchContext = new MethodMatchContext(
                    queryEncoder,
                    currentRepository,
                    entity,
                    context,
                    genericReturnType,
                    element,
                    parametersInRole,
                    typeRoles,
                    parameters,
                    entityResolver,
                    findInterceptors
                );

                for (MethodMatcher finder : methodsMatchers) {
                    MethodMatcher.MethodMatch matcher = finder.match(methodMatchContext);
                    if (matcher == null) {
                        continue;
                    }

                    MethodMatchInfo methodInfo = matcher.buildMatchInfo(methodMatchContext);
                    if (methodInfo == null) {
                        continue;
                    }

                    processMethodInfo(methodMatchContext, methodInfo);
                    return;
                }
                if (matchContext.isPossiblyFailing()) {
                    matchContext.logPossibleFailures();
                } else {
                    String messageStart = matchContext.getUnableToImplementMessage();
                    context.fail(messageStart + "No possible implementations found.", element);
                }
                this.failing = true;
            } catch (MatchFailedException e) {
                context.fail(matchContext.getUnableToImplementMessage() + e.getMessage(), e.getElement() == null ? element : e.getElement());
                this.failing = true;
            } catch (Exception e) {
                if (e instanceof ElementPostponedToNextRoundException || e.getClass().getSimpleName().equals("PostponeToNextRoundException")) {
                    // rethrow postponed and don't fail compilation
                    // this is not ideal since PostponeToNextRoundException is part of inject-java
                    throw e;
                }
                matchContext.fail(e.getMessage());
                this.failing = true;
            }
        }
    }

    private void processMethodInfo(MethodMatchContext methodMatchContext, MethodMatchInfo methodInfo) {
        QueryBuilder queryEncoder = methodMatchContext.getQueryBuilder();
        MethodElement element = methodMatchContext.getMethodElement();

        // populate parameter roles
        for (Map.Entry<String, Element> entry : methodMatchContext.getParametersInRole().entrySet()) {
            methodInfo.addParameterRole(
                entry.getKey(),
                entry.getValue().getName()
            );
        }

        List<QueryParameterBinding> parameterBinding;
        QueryResult queryResult = methodInfo.getQueryResult();
        if (queryResult == null) {
            parameterBinding = null;
        } else {
            parameterBinding = queryResult.getParameterBindings();

            if (methodInfo.isRawQuery()) {

                element.annotate(Query.class, (builder) -> builder.member(DataMethod.META_MEMBER_RAW_QUERY,
                    element.stringValue(Query.class)
                        .map(q -> addRawQueryParameterPlaceholders(queryEncoder, queryResult.getQuery(), queryResult.getQueryParts()))
                        .orElse(null)));

                ClassElement genericReturnType = methodMatchContext.getReturnType();
                if (methodMatchContext.isTypeInRole(genericReturnType, TypeRole.PAGE)
                    || methodMatchContext.isTypeInRole(genericReturnType, TypeRole.CURSORED_PAGE)
                    || element.isPresent(Query.class, "countQuery")
                ) {
                    QueryResult countQueryResult = methodInfo.getCountQueryResult();
                    if (countQueryResult == null) {
                        throw new ProcessingException(element, "Query returns a Page and does not specify a 'countQuery' member.");
                    } else {
                        element.annotate(
                            Query.class,
                            (builder) -> builder.member(DataMethod.META_MEMBER_RAW_COUNT_QUERY, addRawQueryParameterPlaceholders(queryEncoder, countQueryResult.getQuery(), countQueryResult.getQueryParts()))
                        );
                    }
                }

            } else {

                bindAdditionalParameters(methodMatchContext, parameterBinding, queryResult.getAdditionalRequiredParameters());

                QueryResult preparedCount = methodInfo.getCountQueryResult();
                if (preparedCount != null) {
                    element.annotate(Query.class, annotationBuilder -> {
                            annotationBuilder.value(queryResult.getQuery());
                            annotationBuilder.member(DataMethod.META_MEMBER_COUNT_QUERY, preparedCount.getQuery());
                        }
                    );
                } else {
                    element.annotate(Query.class, annotationBuilder -> {
                            annotationBuilder.value(queryResult.getQuery());
                            String update = queryResult.getUpdate();
                            if (StringUtils.isNotEmpty(update)) {
                                annotationBuilder.member("update", update);
                            }
                        }
                    );
                }

                Collection<JoinPath> joinPaths = queryResult.getJoinPaths();
                if (CollectionUtils.isNotEmpty(joinPaths)) {
                    // Only apply the changes if joins aren't empty.
                    // Implementation might choose to return an empty array to skip the modification of existing annotations.
                    element.removeAnnotation(Join.class);
                    joinPaths.forEach(joinPath -> element.annotate(Join.class, builder -> {
                        builder.member("value", joinPath.getPath())
                            .member("type", joinPath.getJoinType());
                        if (joinPath.getAlias().isPresent()) {
                            builder.member("alias", joinPath.getAlias().get());
                        }
                    }));
                }
            }
        }

        annotateQueryResultIfApplicable(element, methodInfo, methodMatchContext.getRootEntity());

        element.annotate(DataMethod.class.getName(), annotationBuilder -> {

            ClassElement runtimeInterceptor = methodInfo.getRuntimeInterceptor();
            if (runtimeInterceptor == null) {
                throw new MatchFailedException("Unable to implement Repository method: " + currentRepository.getSimpleName() + "." + element.getName() + "(..). No possible runtime implementations found.", element);
            }
            annotationBuilder.member(DataMethod.META_MEMBER_INTERCEPTOR, new AnnotationClassValue<>(runtimeInterceptor.getName()));
            annotationBuilder.member(DataMethod.META_MEMBER_ROOT_ENTITY, new AnnotationClassValue<>(methodMatchContext.getRootEntity().getName()));

            if (methodInfo.isDto()) {
                annotationBuilder.member(DataMethod.META_MEMBER_DTO, true);
            }
            if (methodInfo.isOptimisticLock()) {
                annotationBuilder.member(DataMethod.META_MEMBER_OPTIMISTIC_LOCK, true);
            }

            // include the roles
            methodInfo.getParameterRoles().forEach(annotationBuilder::member);

            addQueryDefinition(methodMatchContext,
                annotationBuilder,
                methodInfo.getOperationType(),
                queryResult,
                methodInfo.getResultType(),
                parameterBinding,
                methodInfo.isEncodeEntityParameters());

            QueryResult countQuery = methodInfo.getCountQueryResult();
            if (countQuery != null) {
                List<QueryParameterBinding> countParametersBindings = countQuery.getParameterBindings();
                bindAdditionalParameters(methodMatchContext, countParametersBindings, countQuery.getAdditionalRequiredParameters());

                AnnotationValueBuilder<Annotation> builder = AnnotationValue.builder(DataMethodQuery.class.getName());

                String query = countQuery.getQuery();
                if (methodInfo.isRawQuery()) {
                    query = addRawQueryParameterPlaceholders(queryEncoder, query, countQuery.getQueryParts());
                }

                builder.member(AnnotationMetadata.VALUE_MEMBER, query);
                builder.member(DataMethodQuery.META_MEMBER_NATIVE, element.booleanValue(Query.class,
                    DataMethodQuery.META_MEMBER_NATIVE).orElse(false));

                addQueryDefinition(methodMatchContext,
                    builder,
                    DataMethod.OperationType.COUNT,
                    countQuery,
                    methodMatchContext.getVisitorContext().getClassElement(Long.class).orElseThrow(),
                    countParametersBindings,
                    methodInfo.isEncodeEntityParameters());

                annotationBuilder.member(DataMethod.META_MEMBER_COUNT_QUERY, builder.build());
            }
        });
    }

    private void addQueryDefinition(MethodMatchContext methodMatchContext,
                                    AnnotationValueBuilder<Annotation> annotationBuilder,
                                    DataMethod.OperationType operationType,
                                    QueryResult queryResult,
                                    TypedElement resultType,
                                    List<QueryParameterBinding> parameterBinding,
                                    boolean encodeEntityParameters) {

        if (methodMatchContext.getMethodElement().hasAnnotation(Procedure.class)) {
            annotationBuilder.member(DataMethod.META_MEMBER_PROCEDURE, true);
        }

        annotationBuilder.member(DataMethod.META_MEMBER_OPERATION_TYPE, operationType);

        if (resultType != null) {
            annotationBuilder.member(DataMethod.META_MEMBER_RESULT_TYPE, new AnnotationClassValue<>(resultType.getName()));
            ClassElement type = resultType.getType();
            if (!TypeUtils.isVoid(type)) {
                annotationBuilder.member(DataMethod.META_MEMBER_RESULT_DATA_TYPE, TypeUtils.resolveDataType(type, dataTypes));
            }
        }

        if (queryResult != null) {
            if (parameterBinding.stream().anyMatch(QueryParameterBinding::isExpandable)) {
                annotationBuilder.member(DataMethod.META_MEMBER_EXPANDABLE_QUERY, queryResult.getQueryParts().toArray(new String[0]));
            }

            int max = queryResult.getMax();
            if (max > -1) {
                annotationBuilder.member(DataMethod.META_MEMBER_LIMIT, max);
            }
            long offset = queryResult.getOffset();
            if (offset > 0) {
                annotationBuilder.member(DataMethod.META_MEMBER_OFFSET, offset);
            }
        }

        if (CollectionUtils.isNotEmpty(parameterBinding)) {
            bindParameters(
                methodMatchContext.supportsImplicitQueries(),
                parameterBinding,
                encodeEntityParameters,
                annotationBuilder
            );
        }
    }

    private void bindParameters(boolean supportsImplicitQueries,
                                List<QueryParameterBinding> parameterBinding,
                                boolean finalEncodeEntityParameters,
                                AnnotationValueBuilder<Annotation> annotationBuilder) {

        List<AnnotationValue<?>> annotationValues = new ArrayList<>(parameterBinding.size());
        for (QueryParameterBinding p : parameterBinding) {
            AnnotationValueBuilder<?> builder = AnnotationValue.builder(DataMethodQueryParameter.class);
            if (p.getParameterIndex() != -1) {
                builder.member(DataMethodQueryParameter.META_MEMBER_PARAMETER_INDEX, p.getParameterIndex());
            }
            if (p.getParameterBindingPath() != null) {
                builder.member(DataMethodQueryParameter.META_MEMBER_PARAMETER_BINDING_PATH, p.getParameterBindingPath());
            }
            if (p.getPropertyPath() != null) {
                if (p.getPropertyPath().length == 1) {
                    builder.member(DataMethodQueryParameter.META_MEMBER_PROPERTY, p.getPropertyPath()[0]);
                } else {
                    builder.member(DataMethodQueryParameter.META_MEMBER_PROPERTY_PATH, p.getPropertyPath());
                }
            }
            if (!supportsImplicitQueries && !finalEncodeEntityParameters) {
                builder.member(DataMethodQueryParameter.META_MEMBER_DATA_TYPE, p.getDataType());
            }
            builder.member(DataMethodQueryParameter.META_MEMBER_JSON_DATA_TYPE, p.getJsonDataType());
            if (p.getConverterClassName() != null) {
                builder.member(DataMethodQueryParameter.META_MEMBER_CONVERTER, new AnnotationClassValue<>(p.getConverterClassName()));
            }
            if (p.isAutoPopulated()) {
                builder.member(DataMethodQueryParameter.META_MEMBER_AUTO_POPULATED, true);
            }
            if (p.isRequiresPreviousPopulatedValue()) {
                builder.member(DataMethodQueryParameter.META_MEMBER_REQUIRES_PREVIOUS_POPULATED_VALUES, true);
            }
            if (p.isExpandable()) {
                builder.member(DataMethodQueryParameter.META_MEMBER_EXPANDABLE, true);
            }
            if (p.isExpression()) {
                builder.member(DataMethodQueryParameter.META_MEMBER_EXPRESSION, true);
                if (!supportsImplicitQueries) {
                    builder.member(DataMethodQueryParameter.META_MEMBER_NAME, p.getName());
                }
                Object value = p.getValue();
                if (value != null) {
                    if (value instanceof String expression) {
                        // TODO: Support adding an expression annotation value in Core
                        String originatingClassName = DataMethodQueryParameter.class.getName();
                        String packageName = NameUtils.getPackageName(originatingClassName);
                        String simpleClassName = NameUtils.getSimpleName(originatingClassName);
                        String exprClassName = "%s.$%s%s".formatted(packageName, simpleClassName, EvaluatedExpressionReferenceCounter.EXPR_SUFFIX);

                        Integer expressionIndex = EvaluatedExpressionReferenceCounter.nextIndex(exprClassName);

                        builder.members(Map.of(
                            AnnotationMetadata.VALUE_MEMBER,
                            new EvaluatedExpressionReference(expression, originatingClassName, AnnotationMetadata.VALUE_MEMBER, exprClassName + expressionIndex)
                        ));
                    } else {
                        throw new IllegalStateException("The expression value should be a String!");
                    }
                }
            }
            if (supportsImplicitQueries) {
                builder.member(DataMethodQueryParameter.META_MEMBER_NAME, p.getKey());
            }
            if (p.getRole() != null) {
                builder.member(DataMethodQueryParameter.META_MEMBER_ROLE, p.getRole());
            }
            if (p.getTableAlias() != null) {
                builder.member(DataMethodQueryParameter.META_MEMBER_TABLE_ALIAS, p.getTableAlias());
            }
            annotationValues.add(builder.build());
        }
        AnnotationValue[] annotations = annotationValues.toArray(new AnnotationValue[0]);
        annotationBuilder.member(DataMethod.META_MEMBER_PARAMETERS, annotations);
    }

    private void bindAdditionalParameters(MethodMatchContext methodMatchContext,
                                          List<QueryParameterBinding> parameterBinding,
                                          Map<String, String> params) {
        SourcePersistentEntity entity = methodMatchContext.getRootEntity();
        ParameterElement[] parameters = methodMatchContext.getParameters();

        Map<String, DataType> configuredDataTypes = Utils.getConfiguredDataTypes(methodMatchContext.getRepositoryClass());

        for (ListIterator<QueryParameterBinding> iterator = parameterBinding.listIterator(); iterator.hasNext(); ) {
            QueryParameterBinding queryParameterBinding = iterator.next();
            if (queryParameterBinding instanceof AdditionalParameterBinding additionalParameterBinding) {
                iterator.set(
                    createAdditionalBinding(
                        additionalParameterBinding.bindingContext(),
                        methodMatchContext,
                        entity,
                        parameters,
                        additionalParameterBinding.getName(),
                        configuredDataTypes
                    )
                );
            }
        }

        if (CollectionUtils.isNotEmpty(params)) {
            for (Map.Entry<String, String> param : params.entrySet()) {
                String key = param.getKey();
                String name = param.getValue();

                parameterBinding.add(
                    createAdditionalBinding(
                        BindingParameter.BindingContext.create().name(key),
                        methodMatchContext,
                        entity,
                        parameters,
                        name,
                        configuredDataTypes
                    )
                );

            }
        }
    }

    private QueryParameterBinding createAdditionalBinding(BindingParameter.BindingContext bindingContext,
                                                          MatchContext matchContext,
                                                          SourcePersistentEntity entity,
                                                          ParameterElement[] parameters,
                                                          String name,
                                                          Map<String, DataType> configuredDataTypes) {

        List<AnnotationValue<ParameterExpression>> parameterExpressions = matchContext.getMethodElement()
            .getAnnotationMetadata()
            .getAnnotationValuesByType(ParameterExpression.class);

        Optional<AnnotationValue<ParameterExpression>> parameterExpression = parameterExpressions.stream()
            .filter(av -> av.stringValue("name").orElse("").equals(name))
            .findFirst();

        if (parameterExpression.isPresent()) {
            ClassElement type = RawQueryMethodMatcher.extractExpressionType(matchContext, parameterExpression.orElseThrow());
            return new SourceParameterExpressionImpl(configuredDataTypes, name, type, null)
                .bind(bindingContext);
        }

        ParameterElement parameter = Arrays.stream(parameters)
            .filter(p -> p.stringValue(Parameter.class).orElse(p.getName()).equals(name))
            .findFirst().orElse(null);

        if (parameter == null) {
            throw new MatchFailedException("A @Where(..) definition requires a parameter called [" + name + "] which is not present in the method signature.");
        }

        PersistentPropertyPath propertyPath = entity.getPropertyPath(name);

        bindingContext = bindingContext.incomingMethodParameterProperty(propertyPath)
            .outgoingQueryParameterProperty(propertyPath);

        return new SourceParameterExpressionImpl(configuredDataTypes,
            matchContext.parameters,
            parameter,
            false,
            null)
            .bind(bindingContext);
    }

    private String addRawQueryParameterPlaceholders(QueryBuilder queryEncoder, String query, List<String> queryParts) {
        if (queryEncoder instanceof SqlQueryBuilder sqlQueryBuilder) {
            Iterator<String> iterator = queryParts.iterator();
            String first = iterator.next();
            if (queryParts.size() < 2) {
                return first;
            }
            var sb = new StringBuilder(first);
            int i = 1;
            while (iterator.hasNext()) {
                sb.append(sqlQueryBuilder.formatParameter(i++).getName());
                sb.append(iterator.next());
            }
            return sb.toString();
        }
        return query;
    }

    private SourcePersistentEntity resolvePersistentEntity(MethodElement element, Map<String, Element> parametersInRole) {
        ClassElement returnType = element.getGenericReturnType();
        SourcePersistentEntity entity = resolveEntityForCurrentClass();
        if (entity == null) {
            entity = Utils.resolvePersistentEntity(returnType, entityResolver);
        }

        if (entity != null) {
            List<PersistentProperty> propertiesInRole = entity.getPersistentProperties()
                .stream().filter(pp -> pp.getAnnotationMetadata().hasStereotype(TypeRole.class))
                .collect(Collectors.toList());
            for (PersistentProperty persistentProperty : propertiesInRole) {
                String role = persistentProperty.getAnnotationMetadata().getValue(TypeRole.class, "role", String.class).orElse(null);
                if (role != null) {
                    parametersInRole.put(role, ((SourcePersistentProperty) persistentProperty).getPropertyElement());
                }
            }
            return entity;
        } else {
            throw new MatchFailedException("Could not resolved root entity. Either implement the Repository interface or define the entity as part of the signature", element);
        }
    }

    @Nullable
    private SourcePersistentEntity resolveEntityForCurrentClass() {
        Map<String, ClassElement> typeArguments = currentRepository.getTypeArguments(GenericRepository.class);
        String argName = "E";
        if (typeArguments.isEmpty()) {
            argName = "T";
            typeArguments = currentRepository.getTypeArguments(SPRING_REPO);
        }
        if (!typeArguments.isEmpty()) {
            ClassElement ce = typeArguments.get(argName);
            if (ce != null) {
                return entityResolver.apply(ce);
            }
        }
        return null;
    }

    /**
     * Annotates repository element with {@link EntityRepresentation} if an entity is marked with it.
     *
     * @param classElement the repository class element
     */
    private void annotateEntityRepresentationIfPresent(ClassElement classElement) {
        SourcePersistentEntity entity = resolveEntityForCurrentClass();
        if (entity != null) {
            AnnotationValue<EntityRepresentation> entityRepresentationAnnotationValue = entity.getAnnotation(EntityRepresentation.class);
            if (entityRepresentationAnnotationValue != null) {
                classElement.annotate(entityRepresentationAnnotationValue);
            }
        }
    }

    /**
     * Annotates method element with {@link io.micronaut.data.annotation.QueryResult} if root entity is {@link EntityRepresentation} of JSON type
     * and method is {@link DataMethod.OperationType#QUERY}.
     *
     * @param element    the method element
     * @param methodInfo the method match info
     * @param entity     the root entity
     */
    private void annotateQueryResultIfApplicable(MethodElement element, MethodMatchInfo methodInfo, SourcePersistentEntity entity) {
        if (methodInfo.getOperationType() == DataMethod.OperationType.QUERY && methodInfo.getResultType().equals(entity.getType())) {
            AnnotationValue<EntityRepresentation> entityRepresentationAnnotationValue = entity.getAnnotation(EntityRepresentation.class);
            if (entityRepresentationAnnotationValue != null) {
                EntityRepresentation.Type type = entityRepresentationAnnotationValue.getRequiredValue("type", EntityRepresentation.Type.class);
                String column = entityRepresentationAnnotationValue.getRequiredValue("column", String.class);
                JsonDataType jsonDataType = JsonDataType.DEFAULT;
                io.micronaut.data.annotation.QueryResult.Type queryResultType = type == EntityRepresentation.Type.TABULAR ? io.micronaut.data.annotation.QueryResult.Type.TABULAR : io.micronaut.data.annotation.QueryResult.Type.JSON;
                element.annotate(io.micronaut.data.annotation.QueryResult.class, builder -> builder
                    .member("type", queryResultType)
                    .member("jsonDataType", jsonDataType)
                    .member("column", column));
            }
        }
    }

    /**
     * Generates key for the entityMap using {@link ClassElement}.
     * If class element has generic types then will use all bound generic types in the key like
     * for example {@code Entity<CustomKeyType, CustomValueType>} and for non-generic class element
     * will just return class name.
     * This is needed when there are for example multiple embedded fields with the same type
     * but different generic type argument.
     *
     * @param classElement The class element
     * @return The key for entityMap created from the class element
     */
    private String getClassNameKey(ClassElement classElement) {
        List<? extends ClassElement> boundGenericTypes = classElement.getBoundGenericTypes();
        if (CollectionUtils.isNotEmpty(boundGenericTypes)) {
            StringBuilder keyBuff = new StringBuilder(classElement.getName());
            keyBuff.append("<");
            for (ClassElement boundGenericType : boundGenericTypes) {
                keyBuff.append(boundGenericType.getName());
                keyBuff.append(",");
            }
            keyBuff.deleteCharAt(keyBuff.length() - 1);
            keyBuff.append(">");
            return keyBuff.toString();
        } else {
            return classElement.getName();
        }
    }
}
