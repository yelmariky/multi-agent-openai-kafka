package io.multiagent.core.organization.controller;

import io.multiagent.core.organization.entity.Resource;
import io.multiagent.core.organization.service.ResourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/resources")
public class ResourceController {

    private final ResourceService resourceService;

    @GetMapping
    public List<Resource> list(@RequestParam(required = false, defaultValue = "false") boolean activeOnly) {
        return activeOnly ? resourceService.findActive() : resourceService.findAll();
    }

    @PostMapping
    public ResponseEntity<Resource> create(@RequestBody Resource resource) {
        return ResponseEntity.ok(resourceService.create(resource));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Resource> update(@PathVariable UUID id, @RequestBody Resource updates) {
        return ResponseEntity.ok(resourceService.update(id, updates));
    }
}
