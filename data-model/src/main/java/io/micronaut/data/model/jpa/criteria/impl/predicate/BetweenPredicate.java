/*
 * Copyright 2017-2021 original authors
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
package io.micronaut.data.model.jpa.criteria.impl.predicate;

import io.micronaut.core.annotation.Internal;
import io.micronaut.data.model.jpa.criteria.impl.CriteriaUtils;
import io.micronaut.data.model.jpa.criteria.impl.PredicateVisitor;
import jakarta.persistence.criteria.Expression;

/**
 * The between predicate implementation.
 *
 * @author Denis Stepanov
 * @since 3.2
 */
@Internal
public final class BetweenPredicate extends AbstractPredicate {

    private final Expression<?> value;
    private final Expression<?> from;
    private final Expression<?> to;

    public BetweenPredicate(Expression<?> value,
                            Expression<?> from,
                            Expression<?> to) {
        this.value = value;
        this.from = from;
        this.to = to;
        CriteriaUtils.requireComparableExpression(value);
        CriteriaUtils.requireComparableExpression(from);
        CriteriaUtils.requireComparableExpression(to);
    }

    public Expression<?> getValue() {
        return value;
    }

    public Expression<?> getFrom() {
        return from;
    }

    public Expression<?> getTo() {
        return to;
    }

    @Override
    public void visitPredicate(PredicateVisitor predicateVisitor) {
        predicateVisitor.visit(this);
    }

    @Override
    public String toString() {
        return "BetweenPredicate{" +
            "value=" + value +
            ", from=" + from +
            ", to=" + to +
            '}';
    }
}
