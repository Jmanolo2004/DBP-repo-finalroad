package com.fidegresa.branch;

import com.fidegresa.model.Branch;

/**
 * Local con los nombres que usa el panel web (en español) y también los que usa la app de caja
 * (name, address, latitude, longitude), para que ambos lean la misma respuesta.
 */
public record BranchResponse(Long id, String nombre, String categoria, String direccion, String distrito, double latitud,
                             double longitud, int mapX, int mapY, String horario, boolean activa, long clientes,
                             String name, String address, double latitude, double longitude) {
    public static BranchResponse of(Branch branch, long customers) {
        return new BranchResponse(branch.getId(), branch.getName(), branch.getCategory(), branch.getAddress(), branch.getDistrict(),
                branch.getLatitude(), branch.getLongitude(), branch.getMapX(), branch.getMapY(), branch.getSchedule(), branch.isActive(),
                customers, branch.getName(), branch.getAddress() + ", " + branch.getDistrict(), branch.getLatitude(), branch.getLongitude());
    }
}
