/*
 * Copyright 2002-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.validation.beanvalidation;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.function.Supplier;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

import org.springframework.aop.ProxyMethodInvocation;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.SmartFactoryBean;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.validation.annotation.Validated;

/**
 * An AOP Alliance {@link MethodInterceptor} implementation that delegates to a
 * JSR-303 provider for performing method-level validation on annotated methods.
 *
 * <p>Applicable methods have {@link jakarta.validation.Constraint} annotations on
 * their parameters and/or on their return value (in the latter case specified at
 * the method level, typically as inline annotation).
 *
 * <p>E.g.: {@code public @NotNull Object myValidMethod(@NotNull String arg1, @Max(10) int arg2)}
 *
 * <p>In case of validation errors, the interceptor can raise
 * {@link ConstraintViolationException}, or adapt the violations to
 * {@link MethodValidationResult} and raise {@link MethodValidationException}.
 *
 * <p>Validation groups can be specified through Spring's {@link Validated} annotation
 * at the type level of the containing target class, applying to all public service methods
 * of that class. By default, JSR-303 will validate against its default group only.
 *
 * <p>As of Spring 5.0, this functionality requires a Bean Validation 1.1+ provider.
 *
 * @author Juergen Hoeller
 * @author Rossen Stoyanchev
 * @since 3.1
 * @see MethodValidationPostProcessor
 * @see jakarta.validation.executable.ExecutableValidator
 */
public class MethodValidationInterceptor implements MethodInterceptor {

	private final MethodValidationAdapter validationAdapter;

	private final boolean adaptViolations;


	/**
	 * Create a new MethodValidationInterceptor using a default JSR-303 validator underneath.
	 */
	public MethodValidationInterceptor() {
		this(new MethodValidationAdapter(), false);
	}

	/**
	 * Create a new MethodValidationInterceptor using the given JSR-303 ValidatorFactory.
	 * @param validatorFactory the JSR-303 ValidatorFactory to use
	 */
	public MethodValidationInterceptor(ValidatorFactory validatorFactory) {
		this(new MethodValidationAdapter(validatorFactory), false);
	}

	/**
	 * Create a new MethodValidationInterceptor using the given JSR-303 Validator.
	 * @param validator the JSR-303 Validator to use
	 */
	public MethodValidationInterceptor(Validator validator) {
		this(new MethodValidationAdapter(validator), false);
	}

	/**
	 * Create a new MethodValidationInterceptor for the supplied
	 * (potentially lazily initialized) Validator.
	 * @param validator a Supplier for the Validator to use
	 * @since 6.0
	 */
	public MethodValidationInterceptor(Supplier<Validator> validator) {
		this(validator, false);
	}

	/**
	 * Create a new MethodValidationInterceptor for the supplied
	 * (potentially lazily initialized) Validator.
	 * @param validator a Supplier for the Validator to use
	 * @param adaptViolations whether to adapt {@link ConstraintViolation}s, and
	 * if {@code true}, raise {@link MethodValidationException}, of if
	 * {@code false} raise {@link ConstraintViolationException} instead
	 * @since 6.1
	 */
	public MethodValidationInterceptor(Supplier<Validator> validator, boolean adaptViolations) {
		this(new MethodValidationAdapter(validator), adaptViolations);
	}

	private MethodValidationInterceptor(MethodValidationAdapter validationAdapter, boolean adaptViolations) {
		this.validationAdapter = validationAdapter;
		this.adaptViolations = adaptViolations;
	}


	@Override
	@Nullable
	public Object invoke(MethodInvocation invocation) throws Throwable {
		// Avoid Validator invocation on FactoryBean.getObjectType/isSingleton
		if (isFactoryBeanMetadataMethod(invocation.getMethod())) {
			return invocation.proceed();
		}

		Object target = getTarget(invocation);
		Method method = invocation.getMethod();
		Object[] arguments = invocation.getArguments();
		Class<?>[] groups = determineValidationGroups(invocation);

		Set<ConstraintViolation<Object>> violations;

		if (this.adaptViolations) {
			this.validationAdapter.applyArgumentValidation(target, method, null, arguments, groups);
		}
		else {
			violations = this.validationAdapter.invokeValidatorForArguments(target, method, arguments, groups);
			if (!violations.isEmpty()) {
				throw new ConstraintViolationException(violations);
			}
		}

		Object returnValue = invocation.proceed();

		if (this.adaptViolations) {
			this.validationAdapter.applyReturnValueValidation(target, method, null, arguments, groups);
		}
		else {
			violations = this.validationAdapter.invokeValidatorForReturnValue(target, method, returnValue, groups);
			if (!violations.isEmpty()) {
				throw new ConstraintViolationException(violations);
			}
		}

		return returnValue;
	}

	private static Object getTarget(MethodInvocation invocation) {
		Object target = invocation.getThis();
		if (target == null && invocation instanceof ProxyMethodInvocation methodInvocation) {
			// Allow validation for AOP proxy without a target
			target = methodInvocation.getProxy();
		}
		Assert.state(target != null, "Target must not be null");
		return target;
	}

	private boolean isFactoryBeanMetadataMethod(Method method) {
		Class<?> clazz = method.getDeclaringClass();

		// Call from interface-based proxy handle, allowing for an efficient check?
		if (clazz.isInterface()) {
			return ((clazz == FactoryBean.class || clazz == SmartFactoryBean.class) &&
					!method.getName().equals("getObject"));
		}

		// Call from CGLIB proxy handle, potentially implementing a FactoryBean method?
		Class<?> factoryBeanType = null;
		if (SmartFactoryBean.class.isAssignableFrom(clazz)) {
			factoryBeanType = SmartFactoryBean.class;
		}
		else if (FactoryBean.class.isAssignableFrom(clazz)) {
			factoryBeanType = FactoryBean.class;
		}
		return (factoryBeanType != null && !method.getName().equals("getObject") &&
				ClassUtils.hasMethod(factoryBeanType, method));
	}

	/**
	 * Determine the validation groups to validate against for the given method invocation.
	 * <p>Default are the validation groups as specified in the {@link Validated} annotation
	 * on the method, or on the containing target class of the method, or for an AOP proxy
	 * without a target (with all behavior in advisors), also check on proxied interfaces.
	 * @param invocation the current MethodInvocation
	 * @return the applicable validation groups as a Class array
	 */
	protected Class<?>[] determineValidationGroups(MethodInvocation invocation) {
		Object target = getTarget(invocation);
		return this.validationAdapter.determineValidationGroups(target, invocation.getMethod());
	}

}
