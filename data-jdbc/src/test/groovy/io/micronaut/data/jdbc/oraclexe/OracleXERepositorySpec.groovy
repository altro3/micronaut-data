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
package io.micronaut.data.jdbc.oraclexe

import groovy.transform.Memoized
import io.micronaut.data.tck.entities.Book
import io.micronaut.data.tck.entities.Face
import io.micronaut.data.tck.repositories.*
import io.micronaut.data.tck.tests.AbstractRepositorySpec
import spock.lang.PendingFeature

class OracleXERepositorySpec extends AbstractRepositorySpec implements OracleTestPropertyProvider {

    @Override
    boolean isOracle() {
        return true
    }

    @Memoized
    @Override
    PersonRepository getPersonRepository() {
        return context.getBean(OracleXEPersonRepository)
    }

    @Memoized
    @Override
    OracleXEBookRepository getBookRepository() {
        return context.getBean(OracleXEBookRepository)
    }

    @Memoized
    @Override
    GenreRepository getGenreRepository() {
        return context.getBean(OracleXEGenreRepository)
    }

    @Memoized
    @Override
    AuthorRepository getAuthorRepository() {
        return context.getBean(OracleXEAuthorRepository)
    }

    @Memoized
    @Override
    CompanyRepository getCompanyRepository() {
        return context.getBean(OracleXECompanyRepository)
    }

    @Memoized
    @Override
    BookDtoRepository getBookDtoRepository() {
        return context.getBean(OracleXEBookDtoRepository)
    }

    @Memoized
    @Override
    CountryRepository getCountryRepository() {
        return context.getBean(OracleXECountryRepository)
    }

    @Memoized
    @Override
    CityRepository getCityRepository() {
        return context.getBean(OracleXECityRepository)
    }

    @Memoized
    @Override
    RegionRepository getRegionRepository() {
        return context.getBean(OracleXERegionRepository)
    }

    @Memoized
    @Override
    NoseRepository getNoseRepository() {
        return context.getBean(OracleXENoseRepository)
    }

    @Memoized
    @Override
    FaceRepository getFaceRepository() {
        return context.getBean(OracleXEFaceRepository)
    }

    @Memoized
    @Override
    CountryRegionCityRepository getCountryRegionCityRepository() {
        return context.getBean(OracleXECountryRegionCityRepository)
    }

    @Memoized
    @Override
    UserRoleRepository getUserRoleRepository() {
        return context.getBean(OracleXEUserRoleRepository)
    }

    @Memoized
    @Override
    RoleRepository getRoleRepository() {
        return context.getBean(OracleXERoleRepository)
    }

    @Memoized
    @Override
    UserRepository getUserRepository() {
        return context.getBean(OracleXEUserRepository)
    }

    @Memoized
    @Override
    MealRepository getMealRepository() {
        return context.getBean(OracleXEMealRepository)
    }

    @Memoized
    @Override
    FoodRepository getFoodRepository() {
        return context.getBean(OracleXEFoodRepository)
    }

    @Memoized
    @Override
    StudentRepository getStudentRepository() {
        return context.getBean(OracleXEStudentRepository)
    }

    @Memoized
    @Override
    CarRepository getCarRepository() {
        return context.getBean(OracleXECarRepository)
    }

    @Memoized
    @Override
    BasicTypesRepository getBasicTypeRepository() {
        return context.getBean(OracleXEBasicTypesRepository)
    }

    @Memoized
    @Override
    TimezoneBasicTypesRepository getTimezoneBasicTypeRepository() {
        return context.getBean(OracleXETimezoneBasicTypesRepository)
    }

    @Memoized
    @Override
    PageRepository getPageRepository() {
        return context.getBean(OracleXEPageRepository)
    }

    @Memoized
    @Override
    EntityWithIdClassRepository getEntityWithIdClassRepository() {
        return context.getBean(OracleXEEntityWithIdClassRepository)
    }

    @Memoized
    @Override
    EntityWithIdClass2Repository getEntityWithIdClass2Repository() {
        return context.getBean(OracleXEEntityWithIdClass2Repository)
    }

    @Memoized
    @Override
    ExampleEntityRepository getExampleEntityRepository() {
        return context.getBean(OracleExampleEntityRepository)
    }

    @Override
    protected boolean skipCustomSchemaAndCatalogTest() {
        // ORA-04043: object "FORD"."CARS" does not exist
        return true
    }

    @Override
    protected boolean skipQueryByDataArray() {
        // ORA-00932: inconsistent datatypes: expected - got BLOB
        return true
    }

    void "test procedure"() {
        expect:
            bookRepository.add1(123) == 124
            bookRepository.add1Aliased(123) == 124
    }

    void "test ANY queries"() {
        given:
            saveSampleBooks()
        when:
            def books1 = bookRepository.listNativeBooksWithTitleAnyCollection(null)
        then:
            books1.size() == 0
        when:
            def books2 = bookRepository.listNativeBooksWithTitleAnyCollection(["The Stand", "Along Came a Spider", "FFF"])
        then:
            books2.size() == 2
        when:
            def books3 = bookRepository.listNativeBooksWithTitleAnyCollection([])
        then:
            books3.size() == 0
        when:
            def books4 = bookRepository.listNativeBooksWithTitleAnyArray(null)
        then:
            books4.size() == 0
        when:
            def books5 = bookRepository.listNativeBooksWithTitleAnyArray(new String[]{"The Stand", "Along Came a Spider", "FFF"})
        then:
            books5.size() == 2
        when:
            def books6 = bookRepository.listNativeBooksWithTitleAnyArray(new String[0])
        then:
            books6.size() == 0
        cleanup:
            cleanupBooks()
    }

    @PendingFeature
    void "test update returning book"() {
        given:
            setupBooks()
        when:
            def book = bookRepository.findByTitle("Pet Cemetery")
            book.title = "Xyz"
            Book newBook = bookRepository.updateReturning(book)
            book.title = "old"
        then:
            newBook.title == "Xyz"
    }

    @PendingFeature
    void "test update returning book title"() {
        given:
            setupBooks()
        when:
            def book = bookRepository.findByTitle("Pet Cemetery")
            book.title = "Xyz"
            String newTitle = bookRepository.updateReturningTitle(book)
        then:
            newTitle == "Xyz"
            bookRepository.findById(book.id).get().title == "Xyz"
    }

    @PendingFeature
    void "test update returning book title 2"() {
        given:
            setupBooks()
            def book = bookRepository.findByTitle("Pet Cemetery")
        when:
            String newTitle = bookRepository.updateReturningTitle(book.id, "Xyz")
        then:
            newTitle == "Xyz"
            bookRepository.findById(book.id).get().title == "Xyz"
    }

    @PendingFeature
    void "test update returning book title 3"() {
        given:
            setupBooks()
            def book = bookRepository.findByTitle("Pet Cemetery")
        when:
            String newTitle = bookRepository.updateByIdReturningTitle(book.id, "Xyz")
        then:
            newTitle == "Xyz"
            bookRepository.findById(book.id).get().title == "Xyz"
    }

    void "test native query with colon"() {
        given:
        def face = faceRepository.save(new Face("New"))
        def oracleFaceRepository = (OracleXEFaceRepository) faceRepository
        when:
        def faces = oracleFaceRepository.findAllWithOptionalFilters(null, "2024-01-01")
        then:
        faces
        faces[0].name == face.name
        when:"Call repository void method"
        oracleFaceRepository.lock()
        then:"No error thrown"
        noExceptionThrown()
        cleanup:
        faceRepository.delete(face)
    }

}
