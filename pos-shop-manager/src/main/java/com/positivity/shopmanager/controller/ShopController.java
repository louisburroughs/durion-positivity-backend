package com.positivity.shopmanager.controller;

import com.positivity.shopmanager.dto.PersonDTO;
import com.positivity.shopmanager.dto.ServiceEntityDTO;
import com.positivity.shopmanager.service.ShopService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ShopController {

    private final ShopService shopService;

    @GetMapping("/technicians/{id}/person")
    public ResponseEntity<PersonDTO> getTechnicianPerson(@PathVariable Long id) {
        PersonDTO person = shopService.getTechnicianPerson(id);
        if (person == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(person);
    }

    @GetMapping("/services/{id}/details")
    public ResponseEntity<ServiceEntityDTO> getShopServiceDetails(@PathVariable Long id) {
        ServiceEntityDTO service = shopService.getShopServiceDetails(id);
        if (service == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(service);
    }
}
