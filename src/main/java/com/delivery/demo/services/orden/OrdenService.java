package com.delivery.demo.services.orden;

import com.delivery.demo.entities.comprobantes.DetalleOrden;
import com.delivery.demo.entities.comprobantes.Estado;
import com.delivery.demo.entities.comprobantes.Orden;
import com.delivery.demo.services.base.BaseService;

import java.util.Date;
import java.util.List;

public interface OrdenService extends BaseService<Orden, Long> {
    public Orden save(Orden orden, String clienteId) throws Exception;
    public int calcularTiempoTotalPreparacion (List<DetalleOrden> detalleOrden) throws Exception;
    public Date calcularHorarioEntrega(Date fechaEntrada, int tiempoOrdenActual, boolean delivery) throws Exception;
    public Orden actualizarEstado(Estado estado, Long ordenId) throws Exception;
    public boolean controlStock(List<DetalleOrden> detalles);
    public List<DetalleOrden> removeStock(List<DetalleOrden> detalles) throws Exception;
}
