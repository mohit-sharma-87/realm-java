/*
 * Copyright 2021 Realm Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.realm

import io.realm.entities.AllTypes
import io.realm.entities.SetContainerClass
import io.realm.kotlin.createObject
import io.realm.rule.BlockingLooperThread
import kotlin.reflect.KFunction1
import kotlin.reflect.KFunction2
import kotlin.reflect.KProperty1
import kotlin.test.*

/**
 * Generic tester for all types of unmanaged sets.
 */
class ManagedSetTester<T : Any>(
        private val testerName: String,
        private val mixedType: MixedType? = null,
        private val setGetter: KFunction1<AllTypes, RealmSet<T>>,
        private val setSetter: KFunction2<AllTypes, RealmSet<T>, Unit>,
        private val managedSetGetter: KProperty1<SetContainerClass, RealmSet<T>>,
        private val managedCollectionGetter: KFunction1<AllTypes, RealmList<T>>,
        private val initializedSet: List<T?>,
        private val notPresentValue: T
) : SetTester {

    private lateinit var config: RealmConfiguration
    private lateinit var looperThread: BlockingLooperThread
    private lateinit var realm: Realm

    override fun toString(): String = when (mixedType) {
        null -> "ManagedSet-${testerName}"
        else -> "ManagedSet-${testerName}" + mixedType.name.let { "-$it" }
    }

    override fun setUp(config: RealmConfiguration, looperThread: BlockingLooperThread) {
        this.config = config
        this.looperThread = looperThread
        this.realm = Realm.getInstance(config)
    }

    override fun tearDown() = realm.close()

    override fun isManaged() = assertTrue(initAndAssert().isManaged)

    override fun isValid() = assertTrue(initAndAssert().isValid)

    override fun isFrozen() = Unit          // Tested in frozen

    override fun size() {
        val set = initAndAssert()
        assertEquals(0, set.size)
        realm.executeTransaction {
            initializedSet.forEach { value ->
                set.add(value)
            }
        }
        assertEquals(initializedSet.size, set.size)
    }

    override fun isEmpty() {
        val set = initAndAssert()
        assertTrue(set.isEmpty())
        realm.executeTransaction {
            initializedSet.forEach { value ->
                set.add(value)
            }
        }
        assertFalse(set.isEmpty())
    }

    override fun contains() {
        val set = initAndAssert()
        assertFalse(set.contains(notPresentValue))
        realm.executeTransaction {
            set.add(notPresentValue)
        }
        assertTrue(set.contains(notPresentValue))
    }

    override fun iterator() {
        val set = initAndAssert()
        assertNotNull(set.iterator())
        realm.executeTransaction {
            set.addAll(initializedSet)
        }
        set.forEach { value ->
            assertTrue(initializedSet.contains(value))
        }
    }

    override fun toArray() {
        // TODO
    }

    override fun add() {
        val set = initAndAssert()
        assertEquals(0, set.size)
        realm.executeTransaction {
            // Adding a value for the first time returns true
            initializedSet.forEach { value ->
                assertTrue(set.add(value))
            }
            // Adding an existing value returns false
            initializedSet.forEach { value ->
                assertFalse(set.add(value))
            }
        }

        // FIXME: assert sets are equal
    }

    override fun remove() {
        val set = initAndAssert()
        assertEquals(0, set.size)
        realm.executeTransaction {
            initializedSet.forEach { value ->
                set.add(value)
                assertTrue(set.remove(value))
            }
            assertFalse(set.remove(notPresentValue))
        }
        assertEquals(0, set.size)
    }

    override fun containsAll() {
        val set = initAndAssert()
        realm.executeTransaction {
            initializedSet.forEach { value ->
                set.add(value)
            }
        }

        // Contains an unmanaged collection
        assertTrue(set.containsAll(initializedSet))

        // Does not contain an unmanaged collection
        assertFalse(set.containsAll(listOf(notPresentValue)))

        // Contains a managed set
        assertTrue(set.containsAll(set))

        // Contains a managed list with the same elements
        val sameValuesContainer = createAllTypesManagedContainerAndAssert(realm, "sameValues")
        val sameValuesManagedList = managedCollectionGetter.call(sameValuesContainer)
        realm.executeTransaction {
            sameValuesManagedList.addAll(initializedSet)
        }
        assertTrue(set.containsAll(sameValuesManagedList))

        // Does not contain a managed list with the other elements
        val differentValuesContainer = createAllTypesManagedContainerAndAssert(realm, "differentValues")
        val differentValuesManagedList = managedCollectionGetter.call(differentValuesContainer)
        realm.executeTransaction {
            differentValuesManagedList.add(notPresentValue)
        }
        assertFalse(set.containsAll(differentValuesManagedList))

        // Does not contain an empty managed list
        val emptyValuesContainer = createAllTypesManagedContainerAndAssert(realm, "emptyValues")
        val emptyValuesManagedList = managedCollectionGetter.call(emptyValuesContainer)
        assertFalse(set.containsAll(emptyValuesManagedList))

        // Does not contain an empty collection
        assertFalse(set.containsAll(listOf()))

        // FIXME: Fails if passed null fails due to Kotlin generating bytecode that doesn't
        //  allow using TestHelper.getNull() when the generic type is not upper-bound
//        assertFailsWith<NullPointerException> {
//            set.containsAll(TestHelper.getNull())
//        }
    }

    override fun addAll() {
        // FIXME: add cases for managed lists just as we do in containsAll
        val set = initAndAssert()
        assertEquals(0, set.size)
        realm.executeTransaction { transactionRealm ->
            // Check set changed after adding collection
            assertTrue(set.addAll(initializedSet))

            // Set does not change if we add the same data
            assertFalse(set.addAll(initializedSet))

            // Set does not change if we add the same data from a managed set
            val managedSameSet = managedSetGetter.get(transactionRealm.createObject())
            assertNotNull(managedSameSet)
            managedSameSet.addAll(initializedSet)
            assertFalse(set.addAll(managedSameSet as Collection<T>))

            // Set does not change if we add itself to it
            assertFalse(set.addAll(set))

            // Check set changed after adding collection from a managed set
            val managedSet = managedSetGetter.get(transactionRealm.createObject())
            assertNotNull(managedSet)
            managedSet.add(notPresentValue)
            assertTrue(set.addAll(managedSet as Collection<T>))

            // Fails if passed null
            assertFailsWith<NullPointerException> {
                set.addAll(TestHelper.getNull())
            }
        }
    }

    override fun retainAll() {
        // FIXME: add cases for managed lists just as we do in containsAll
        val set = initAndAssert()
        assertEquals(0, set.size)
        realm.executeTransaction { transactionRealm ->
            assertTrue(set.isEmpty())

            // Check empty set does not change after intersecting it with a collection
            assertTrue(set.isEmpty())
            assertFalse(set.retainAll(initializedSet))

            // Add values to set and intersect it the same value - check the set does not change
            set.addAll(initializedSet)
            assertFalse(set.isEmpty())
            assertFalse(set.retainAll(initializedSet))
            assertEquals(initializedSet.size, set.size)

            // Intersect with an empty collection - check set does not change
            assertFalse(set.retainAll(listOf()))
            assertFalse(set.isEmpty())

            // Now intersect with something else - check set changes
            assertTrue(set.retainAll(listOf(notPresentValue)))
            assertTrue(set.isEmpty())

            // Set does not change if we intersect it with another set containing the same elements
            set.addAll(initializedSet)
            val managedSameSet = managedSetGetter.get(transactionRealm.createObject())
            assertNotNull(managedSameSet)
            managedSameSet.addAll(initializedSet)
            assertFalse(set.retainAll(managedSameSet as Collection<T>))
            assertEquals(initializedSet.size, set.size)

            // Set does not change if we intersect it with itself
            set.clear()
            set.addAll(initializedSet)
            assertFalse(set.isEmpty())
            assertFalse(set.retainAll(set))
            assertFalse(set.isEmpty())

            // Intersect with a managed set not containing any elements from the original set
            set.clear()
            set.addAll(initializedSet)
            assertFalse(set.isEmpty())
            val managedSet = managedSetGetter.get(transactionRealm.createObject())
            assertNotNull(managedSet)
            managedSet.add(notPresentValue)
            assertTrue(set.retainAll(managedSet as Collection<T>))
            assertTrue(set.isEmpty())
        }
    }

    override fun removeAll() {
        // FIXME: add cases for managed lists just as we do in containsAll
        val set = initAndAssert()
        assertEquals(0, set.size)
        realm.executeTransaction { transactionRealm ->
            // Check empty set does not change after removing a collection
            assertTrue(set.isEmpty())
            assertFalse(set.removeAll(initializedSet))
            assertTrue(set.isEmpty())

            // Add values to set and remove all - check the set changes
            set.addAll(initializedSet)
            assertFalse(set.isEmpty())
            assertTrue(set.removeAll(initializedSet))
            assertTrue(set.isEmpty())

            // Add values again and remove empty collection - check set does not change
            set.addAll(initializedSet)
            assertFalse(set.removeAll(listOf()))
            assertFalse(set.isEmpty())

            // Now remove something else - check set does not change
            assertFalse(set.removeAll(listOf(notPresentValue)))

            // Set does change if we remove all its items using another set containing the same elements
            set.addAll(initializedSet)
            assertFalse(set.isEmpty())
            val managedSameSet = managedSetGetter.get(transactionRealm.createObject())
            assertNotNull(managedSameSet)
            managedSameSet.addAll(initializedSet)
            assertTrue(set.removeAll(managedSameSet as Collection<T>))

            // Set does change if we remove all its items using itself
            set.clear()
            set.addAll(initializedSet)
            assertFalse(set.isEmpty())
            assertTrue(set.removeAll(set))
            assertTrue(set.isEmpty())

            // Add values again and remove something else from a managed set
            set.addAll(initializedSet)
            assertFalse(set.isEmpty())
            val managedSet = managedSetGetter.get(transactionRealm.createObject())
            assertNotNull(managedSet)
            managedSet.add(notPresentValue)
            assertFalse(set.removeAll(managedSet as Collection<T>))

            // FIXME: Fails if passed null fails due to Kotlin generating bytecode that doesn't
            //  allow using TestHelper.getNull() when the generic type is not upper-bound
//            assertFailsWith<NullPointerException> {
//                set.removeAll(TestHelper.getNull())
//            }
        }
    }

    override fun clear() {
        val set = initAndAssert()
        realm.executeTransaction {
            set.add(notPresentValue)
            assertEquals(1, set.size)
            set.clear()
            assertEquals(0, set.size)
        }
    }

    override fun freeze() {
        val set = initAndAssert()
        realm.executeTransaction {
            set.addAll(initializedSet)
        }

        val frozenSet = set.freeze()
        assertFalse(set.isFrozen)
        assertTrue(frozenSet.isFrozen)
        assertEquals(set.size, frozenSet.size)
    }

    //----------------------------------
    // Private stuff
    //----------------------------------

    private fun initAndAssert(realm: Realm = this.realm): RealmSet<T> {
        val allTypesObject = createAllTypesManagedContainerAndAssert(realm)
        assertNotNull(allTypesObject)
        return setGetter.call(allTypesObject)
    }
}

fun managedSetFactory(): List<SetTester> {
    val primitiveTesters: List<SetTester> = SetSupportedType.values().mapNotNull { supportedType ->
        when (supportedType) {
//            SetSupportedType.LONG ->
//                UnmanagedSetTester<Long>(
//                        testerName = "Long",
//                        values = listOf(VALUE_NUMERIC_HELLO.toLong(), VALUE_NUMERIC_BYE.toLong(), null),
//                        notPresentValue = VALUE_NUMERIC_NOT_PRESENT.toLong()
//                )
//            SetSupportedType.INTEGER ->
//                UnmanagedSetTester<Int>(
//                        testerName = "Int",
//                        values = listOf(VALUE_NUMERIC_HELLO, VALUE_NUMERIC_BYE),
//                        notPresentValue = VALUE_NUMERIC_NOT_PRESENT
//                )
//            SetSupportedType.SHORT ->
//                UnmanagedSetTester<Short>(
//                        testerName = "Short",
//                        values = listOf(VALUE_NUMERIC_HELLO.toShort(), VALUE_NUMERIC_BYE.toShort(), null),
//                        notPresentValue = VALUE_NUMERIC_NOT_PRESENT.toShort()
//                )
//            SetSupportedType.BYTE ->
//                UnmanagedSetTester<Byte>(
//                        testerName = "Byte",
//                        values = listOf(VALUE_NUMERIC_HELLO.toByte(), VALUE_NUMERIC_BYE.toByte(), null),
//                        notPresentValue = VALUE_NUMERIC_NOT_PRESENT.toByte()
//                )
//            SetSupportedType.FLOAT ->
//                UnmanagedSetTester<Float>(
//                        testerName = "Float",
//                        values = listOf(VALUE_NUMERIC_HELLO.toFloat(), VALUE_NUMERIC_BYE.toFloat(), null),
//                        notPresentValue = VALUE_NUMERIC_NOT_PRESENT.toFloat()
//                )
//            SetSupportedType.DOUBLE ->
//                UnmanagedSetTester<Double>(
//                        testerName = "Double",
//                        values = listOf(VALUE_NUMERIC_HELLO.toDouble(), VALUE_NUMERIC_BYE.toDouble(), null),
//                        notPresentValue = VALUE_NUMERIC_NOT_PRESENT.toDouble()
//                )
            SetSupportedType.STRING ->
                ManagedSetTester<String>(
                        testerName = "String",
                        setGetter = AllTypes::getColumnStringSet,
                        setSetter = AllTypes::setColumnStringSet,
                        managedSetGetter = SetContainerClass::myStringSet,
                        managedCollectionGetter = AllTypes::getColumnStringList,
                        initializedSet = listOf(VALUE_STRING_HELLO, VALUE_STRING_BYE, null),
                        notPresentValue = VALUE_STRING_NOT_PRESENT
                )
//            SetSupportedType.BOOLEAN ->
//                UnmanagedSetTester<Boolean>(
//                        testerName = "Boolean",
//                        values = listOf(VALUE_BOOLEAN_HELLO, null),
//                        notPresentValue = VALUE_BOOLEAN_NOT_PRESENT
//                )
//            SetSupportedType.DATE ->
//                UnmanagedSetTester<Date>(
//                        testerName = "Date",
//                        values = listOf(VALUE_DATE_HELLO, VALUE_DATE_BYE, null),
//                        notPresentValue = VALUE_DATE_NOT_PRESENT
//                )
//            SetSupportedType.DECIMAL128 ->
//                UnmanagedSetTester<Decimal128>(
//                        testerName = "Decimal128",
//                        values = listOf(VALUE_DECIMAL128_HELLO, VALUE_DECIMAL128_BYE, null),
//                        notPresentValue = VALUE_DECIMAL128_NOT_PRESENT
//                )
//            SetSupportedType.BINARY ->
//                UnmanagedSetTester<ByteArray>(
//                        testerName = "ByteArray",
//                        values = listOf(VALUE_BINARY_HELLO, VALUE_BINARY_BYE, null),
//                        notPresentValue = VALUE_BINARY_NOT_PRESENT
//                )
//            SetSupportedType.OBJECT_ID ->
//                UnmanagedSetTester<ObjectId>(
//                        testerName = "ObjectId",
//                        values = listOf(VALUE_OBJECT_ID_HELLO, VALUE_OBJECT_ID_BYE, null),
//                        notPresentValue = VALUE_OBJECT_ID_NOT_PRESENT
//                )
//            SetSupportedType.UUID ->
//                UnmanagedSetTester<UUID>(
//                        testerName = "UUID",
//                        values = listOf(VALUE_UUID_HELLO, VALUE_UUID_BYE, null),
//                        notPresentValue = VALUE_UUID_NOT_PRESENT
//                )
//            SetSupportedType.LINK ->
//                UnmanagedSetTester<RealmModel>(
//                        testerName = "UnmanagedRealmModel",
//                        values = listOf(VALUE_LINK_HELLO, VALUE_LINK_BYE, null),
//                        notPresentValue = VALUE_LINK_NOT_PRESENT
//                )
            // Ignore Mixed in this switch
            else -> null
        }
    }

    // Create Mixed testers now
    val mixedTesters = MixedType.values().map { mixedType ->
        UnmanagedSetTester<Mixed>(
                "UnmanagedSetMixed-${mixedType.name}",
                getMixedValues(mixedType),
                VALUE_MIXED_NOT_PRESENT
        )
    }

    // Put the together
//    return primitiveTesters.plus(mixedTesters)
    return primitiveTesters
}
