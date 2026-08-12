package com.positivity.nhtsa.internal.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.nhtsa.internal.entity.Make;
import com.positivity.nhtsa.internal.entity.Manufacturer;
import com.positivity.nhtsa.internal.entity.Model;
import com.positivity.nhtsa.internal.entity.VehicleType;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * First tests for {@link VehicleReferenceMapper} — this module was at 0%.
 *
 * <p>
 * Every method here flattens a JPA association to its id, and each one guards
 * the parent being null. That guard is the whole point: these entities are
 * loaded from a cache table that is populated incrementally from NHTSA, so a
 * {@code Make} whose {@code Manufacturer} has not been fetched yet is an
 * ordinary state, not a broken row. Dropping the guard turns a partially
 * populated cache into a NullPointerException on a read endpoint.
 */
@DisplayName("VehicleReferenceMapper — entity to response flattening")
class VehicleReferenceMapperTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID PARENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private static Manufacturer manufacturer() {
        Manufacturer manufacturer = new Manufacturer();
        manufacturer.setId(PARENT_ID);
        manufacturer.setName("Toyota Motor Corporation");
        return manufacturer;
    }

    private static Make make(Manufacturer parent) {
        Make make = new Make();
        make.setId(ID);
        make.setName("Toyota");
        make.setManufacturer(parent);
        return make;
    }

    @Nested
    @DisplayName("populated associations")
    class Populated {

        @Test
        @DisplayName("flattens a manufacturer")
        void manufacturerResponse() {
            ManufacturerResponse response = VehicleReferenceMapper.toManufacturerResponse(manufacturer());

            assertThat(response.getId()).isEqualTo(PARENT_ID);
            assertThat(response.getName()).isEqualTo("Toyota Motor Corporation");
        }

        @Test
        @DisplayName("flattens a make to its manufacturer id")
        void makeResponse() {
            MakeResponse response = VehicleReferenceMapper.toMakeResponse(make(manufacturer()));

            assertThat(response.getId()).isEqualTo(ID);
            assertThat(response.getName()).isEqualTo("Toyota");
            // The association is flattened to an id, not embedded — the response contract is flat.
            assertThat(response.getManufacturerId()).isEqualTo(PARENT_ID);
        }

        @Test
        @DisplayName("flattens a model to its make id")
        void modelResponse() {
            Model model = new Model();
            model.setId(ID);
            model.setName("Corolla");
            model.setMake(make(manufacturer()));

            ModelResponse response = VehicleReferenceMapper.toModelResponse(model);

            assertThat(response.getId()).isEqualTo(ID);
            assertThat(response.getName()).isEqualTo("Corolla");
            assertThat(response.getMakeId()).isEqualTo(ID);
        }

        @Test
        @DisplayName("flattens a vehicle type, keeping the NHTSA-assigned type id")
        void vehicleTypeResponse() {
            VehicleType type = new VehicleType();
            type.setId(ID);
            type.setMake(make(manufacturer()));
            type.setVehicleTypeName("Passenger Car");

            VehicleTypeResponse response = VehicleReferenceMapper.toVehicleTypeResponse(type);

            assertThat(response.getId()).isEqualTo(ID);
            assertThat(response.getMakeId()).isEqualTo(ID);
            assertThat(response.getVehicleTypeName()).isEqualTo("Passenger Car");
        }
    }

    @Nested
    @DisplayName("partially populated cache rows")
    class Unpopulated {

        @Test
        @DisplayName("maps a make whose manufacturer has not been cached yet")
        void makeWithoutManufacturer() {
            // Normal state while the cache fills incrementally from NHTSA — not a broken row.
            MakeResponse response = VehicleReferenceMapper.toMakeResponse(make(null));

            assertThat(response.getName()).isEqualTo("Toyota");
            assertThat(response.getManufacturerId()).isNull();
        }

        @Test
        @DisplayName("maps a model whose make has not been cached yet")
        void modelWithoutMake() {
            Model model = new Model();
            model.setId(ID);
            model.setName("Corolla");

            ModelResponse response = VehicleReferenceMapper.toModelResponse(model);

            assertThat(response.getMakeId()).isNull();
        }

        @Test
        @DisplayName("maps a vehicle type whose make has not been cached yet")
        void vehicleTypeWithoutMake() {
            VehicleType type = new VehicleType();
            type.setId(ID);
            type.setVehicleTypeName("Truck");

            VehicleTypeResponse response = VehicleReferenceMapper.toVehicleTypeResponse(type);

            assertThat(response.getMakeId()).isNull();
            assertThat(response.getVehicleTypeName()).isEqualTo("Truck");
        }
    }
}
