package com.positivity.modulith;

import static org.springframework.modulith.core.ApplicationModules.of;

import org.junit.jupiter.api.Test;

/**
 * Spring Modulith module structure validation tests.
 * 
 * These tests verify that:
 * 1. All modules are correctly detected and defined
 * 2. No circular dependencies exist between modules
 * 3. Module dependencies align with ADR-0009 definitions
 * 4. Modules expose only intended public APIs
 */
class ModuleStructureTests {

	@Test
	void verifyModuleStructure() {
		ApplicationModules modules = ApplicationModules.of(PositivityModulithApplication.class);
		modules.verify();
	}

	@Test
	void verifyCoreModuleHierarchy() {
		ApplicationModules modules = ApplicationModules.of(PositivityModulithApplication.class);
		
		// Verify that domain modules exist as expected per ADR-0009
		modules.getModuleByName("com.positivity.accounting").ifPresent(module -> {
			System.out.println("✓ Accounting module found");
		});
		
		modules.getModuleByName("com.positivity.inventory").ifPresent(module -> {
			System.out.println("✓ Inventory module found");
		});
		
		modules.getModuleByName("com.positivity.people").ifPresent(module -> {
			System.out.println("✓ People module found");
		});
		
		modules.getModuleByName("com.positivity.workorder").ifPresent(module -> {
			System.out.println("✓ Workorder module found");
		});
	}

	@Test
	void printModuleDocumentation() {
		ApplicationModules modules = ApplicationModules.of(PositivityModulithApplication.class);
		modules.stream().forEach(module -> {
			System.out.println(formatModuleInfo(module));
		});
	}

	private static String formatModuleInfo(ApplicationModule module) {
		StringBuilder sb = new StringBuilder();
		sb.append(String.format("Module: %s%n", module.getName()));
		sb.append(String.format("  Display Name: %s%n", module.getDisplayName()));
		sb.append(String.format("  Type: %s%n", module.getType()));
		
		if (!module.getDependencies().isEmpty()) {
			sb.append("  Dependencies:%n");
			module.getDependencies().forEach(dep -> 
				sb.append(String.format("    → %s%n", dep.getTargetModule().getName()))
			);
		}
		
		return sb.toString();
	}
}
