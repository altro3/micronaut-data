/*
 * Copyright 2017-2019 original authors
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
package io.micronaut.data.tck.entities;

import edu.umd.cs.findbugs.annotations.Nullable;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import javax.persistence.GeneratedValue;

@MappedEntity
public class Plant {
    @Id
    @GeneratedValue
    private Long id;

    private final String name;
    @Nullable
    private final Nursery nursery;
    @Nullable
    private Integer maxHeight;

    public Plant(String name, @Nullable Nursery nursery) {
        this.name = name;
        this.nursery = nursery;
    }

    public String getName() {
        return name;
    }

    @Nullable
    public Nursery getNursery() {
        return nursery;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getMaxHeight() {
        return maxHeight;
    }

    public void setMaxHeight(Integer maxHeight) {
        this.maxHeight = maxHeight;
    }
}