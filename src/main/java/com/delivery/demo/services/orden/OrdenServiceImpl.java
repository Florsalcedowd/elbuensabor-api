package com.delivery.demo.services.orden;

import com.delivery.demo.entities.articulos.*;
import com.delivery.demo.entities.comprobantes.DetalleOrden;
import com.delivery.demo.entities.comprobantes.Estado;
import com.delivery.demo.entities.comprobantes.Orden;
import com.delivery.demo.entities.usuarios.Cliente;
import com.delivery.demo.entities.usuarios.Empleado;
import com.delivery.demo.entities.usuarios.Usuario;
import com.delivery.demo.repositories.BaseRepository;
import com.delivery.demo.repositories.articulos.ArticuloInsumoRepository;
import com.delivery.demo.repositories.comprobantes.EstadoRepository;
import com.delivery.demo.repositories.usuarios.ClienteRepository;
import com.delivery.demo.repositories.usuarios.UsuarioRepository;
import com.delivery.demo.services.base.BaseServiceImpl;
import com.delivery.demo.specifications.SearchSpecification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.*;

@Service
public class OrdenServiceImpl extends BaseServiceImpl<Orden, Long> implements OrdenService {

    @Autowired
    ArticuloInsumoRepository insumoRepository;

    @Autowired
    UsuarioRepository usuarioRepository;

    @Autowired
    ClienteRepository clienteRepository;

    @Autowired
    EstadoRepository estadoRepository;

    public OrdenServiceImpl(BaseRepository<Orden, Long> baseRepository) {
        super(baseRepository);
    }


    SearchSpecification<Orden> spec = new SearchSpecification<Orden>();
    Specification<Orden> isNotDeleted = spec.isNotDeleted();
    SearchSpecification<Estado> specEstado = new SearchSpecification<Estado>();
    SearchSpecification<Cliente> specCliente = new SearchSpecification<Cliente>();

    /*
     * @desc This method gets all orders paged and sorts and filter data
     * @return Map<String, Object> ordenes or new Exception()
     * */
    @Override
    public Map<String, Object> findAll(String filter, int page, int size, String sortBy, String direction) throws Exception {
        try {
            Pageable pageable;
            if (direction.equals("desc")) {
                pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, sortBy));
            } else {
                pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, sortBy));
            }

            Page<Orden> entityPage;

            if(filter == null || filter.equals("")){
                entityPage = baseRepository.findAll(Specification.where(isNotDeleted),pageable);
            } else {
                Specification<Orden> filterByEstado = spec.findByEstado(filter);
                Specification<Orden> filterById = spec.findByProperty("id", filter);
                Specification<Orden> filterByFormaPago = spec.findByProperty("formaPago", filter);
                Specification<Orden> filterByNombreCliente = spec.findByForeignAttribute("cliente", "nombre", filter);
                Specification<Orden> filterByApellidoCliente = spec.findByForeignAttribute("cliente", "apellido", filter);
                Specification<Orden> filterByClienteUid = spec.findByForeignAttribute("cliente", "uid", filter);

                entityPage = baseRepository.findAll(Specification.where(isNotDeleted)
                        .and(Specification.where(filterByEstado)
                                .or(filterByClienteUid)
                                .or(filterByNombreCliente)
                                .or(filterByApellidoCliente)
                                .or(filterById)
                                .or(filterByFormaPago)
                                ), pageable);
            }

            List<Orden> entities = entityPage.getContent();

            Map<String, Object> response = new HashMap<>();
            response.put("payload", entities);
            response.put("length", entityPage.getTotalElements());

            return response;
        } catch (Exception e) {
            throw new Exception(e.getMessage());
        }
    }

    /*
     * @desc This method gets a list of orders and filters data if filter string exists
     * @return List<Orden> ordenes or new Exception()
     * */
    @Override
    public List<Orden> findAll(String filter) throws Exception {
        try{

            if(filter == null || filter.equals("")){
                return baseRepository.findAll(Specification.where(isNotDeleted));
            } else {
                Specification<Orden> filterByEstado = spec.findByEstado(filter);
                Specification<Orden> filterById = spec.findByProperty("id", filter);
                Specification<Orden> filterByFormaPago = spec.findByProperty("formaPago", filter);
                Specification<Orden> filterByNombreCliente = spec.findByForeignAttribute("cliente", "nombre", filter);
                Specification<Orden> filterByApellidoCliente = spec.findByForeignAttribute("cliente", "apellido", filter);
                Specification<Orden> filterByClienteUid = spec.findByForeignAttribute("cliente", "uid", filter);

                return baseRepository.findAll(Specification.where(isNotDeleted).and(Specification.where(filterByEstado)
                        .or(filterById)
                        .or(filterByClienteUid)
                        .or(filterByFormaPago)
                        .or(filterByNombreCliente)
                        .or(filterByApellidoCliente)
                ));
            }

        } catch (Exception e){
            throw new Exception(e.getMessage());
        }
    }

    /*
    * @desc This method completes the information of an order and saves it in the database
    * @return Entity "Orden" saved
    * */
    @Override
    public Orden save(Orden orden, String clienteUid) throws Exception {
        try {

            Specification<Cliente> filterByUid = specCliente.findByUid(clienteUid);
            Optional<Cliente> cliente = clienteRepository.findOne(Specification.where(filterByUid));
            orden.setCliente(cliente.get());

            /* FECHA */
            Timestamp timestamp = new Timestamp(System.currentTimeMillis());
            orden.setFecha(timestamp);
            orden.setUltimaActualizacion(timestamp);

            /* ESTADO */
            Specification<Estado> filterByDenominacion = specEstado.findByProperty("denominacion", "pendiente");
            Optional<Estado> estado = estadoRepository.findOne(Specification.where(filterByDenominacion));
            orden.setEstado(estado.get());

            /* TIEMPO PREPARACION */
            orden.setTiempoTotalPreparacion(this.calcularTiempoTotalPreparacion(orden.getDetalles()));

            /* HORARIO ENTREGA */
            orden.setHorarioEntrega(this.calcularHorarioEntrega(orden.getFecha(), orden.getTiempoTotalPreparacion(), orden.isDelivery()));

            if(this.controlStock(orden.getDetalles())){
                orden = baseRepository.save(orden);
                return orden;
            } else {
                throw new Exception("Uno o más productos están fuera de stock");
            }

        } catch (Exception e) {

            throw new Exception(e.getMessage());

        }
    }

    /*
     * @desc This method calculates the estimated delivery time of an order
     *       taking into account the orders found in the kitchen
     * @return Date horarioEntrega
     * */
    @Override
    public Date calcularHorarioEntrega(Date fechaEntrada, int tiempoOrdenActual, boolean delivery) throws Exception {
        try{

            SearchSpecification<Orden> spec = new SearchSpecification<Orden>();
            Specification<Orden> isNotDeleted = spec.isNotDeleted();

            Specification<Orden> filterByEnProceso = spec.findByEstado("en proceso");
            Specification<Orden> filterByDemorado = spec.findByEstado("demorado");
            List<Orden> ordenesEnCocina = baseRepository.findAll(Specification.where(isNotDeleted).and(Specification.where(filterByEnProceso).or(filterByDemorado)));

            Calendar calendar = Calendar.getInstance();
            calendar.setTime(fechaEntrada);

            if(ordenesEnCocina.size() > 0) {
                int tiempoTotalOrdenes = 0;

                for (Orden ordenAux: ordenesEnCocina){
                    tiempoTotalOrdenes += ordenAux.getTiempoTotalPreparacion();
                }

                SearchSpecification<Usuario> userSpec = new SearchSpecification<Usuario>();
                Specification<Usuario> filterByCocinero = userSpec.findByForeignAttribute("rol", "denominacion", "cocinero");
                long cantidadCocinero = usuarioRepository.count(Specification.where(filterByCocinero));

                int tiempoEspera = (int) (tiempoTotalOrdenes / cantidadCocinero);

                calendar.add(Calendar.MINUTE, tiempoEspera);
            }

            calendar.add(Calendar.MINUTE, tiempoOrdenActual);

            if(delivery){
                calendar.add(Calendar.MINUTE, 10);
            }

            return calendar.getTime();
        } catch (Exception e){
            throw new Exception(e.getMessage());
        }
    }

    /*
     * @desc This method calculates the total cooking time in minutes of the order
     *       taking into account the cooking time of each manufactured product that it includes
     * @return int tiempoTotal or 0
     * */
    @Override
    public int calcularTiempoTotalPreparacion(List<DetalleOrden> detallesOrden){
        if (detallesOrden.size() > 0){
            int tiempoTotal = 0;

            for (DetalleOrden detalle: detallesOrden){
                if(detalle.getArticuloManufacturado() != null){
                    tiempoTotal += detalle.getArticuloManufacturado().getTiempoEstimadoCocina();
                }
            }
            return tiempoTotal;
        } else {
            return 0;
        }
    }


    /*
     * @desc This method updates the order status and performs different operations
     * according to the selected status:
     * DEMORADO: adds 10' to the order delivery time
     * EN PROCESO: calls controlStock(). If true calls removeStock(), else set order's state to "CANCELADO"
     * @return Orden ordenUpdated or new Exception()
     * */
    @Override
    public Orden actualizarEstado(Estado estado, Long ordenId) throws Exception {
        try {
            Optional<Orden> ordenOpcional = baseRepository.findById(ordenId);
            Orden orden = ordenOpcional.get();

            orden.setEstado(estado);

            if(estado.getDenominacion().equals("demorado")){
                Calendar calendar = Calendar.getInstance();
                calendar.setTime(orden.getHorarioEntrega());
                calendar.add(Calendar.MINUTE, 10);
                orden.setHorarioEntrega(calendar.getTime());
            } else if (estado.getDenominacion().equals("en proceso")){
                if(this.controlStock(orden.getDetalles())){
                    orden.setDetalles(this.removeStock(orden.getDetalles()));
                } else {
                    Specification<Estado> cancelado = specEstado.findByProperty("denominacion", "cancelado");
                    Optional<Estado> estadoCancelado = estadoRepository.findOne(Specification.where(cancelado));

                    orden.setEstado(estadoCancelado.get());
                }
            }

            Timestamp timestamp = new Timestamp(System.currentTimeMillis());
            orden.setUltimaActualizacion(timestamp);

            orden = baseRepository.save(orden);

            return orden;

        } catch (Exception e) {

            throw new Exception(e.getMessage());

        }
    }

    /*
     * @desc Sets the order carrier and change order's state to "EN CAMINO"
     * @return Orden ordenUpdated
     * */
    @Override
    public Orden addRepartidor(Empleado repartidor, Long ordenId) throws Exception {
        try{

            Optional<Orden> ordenOptional = baseRepository.findById(ordenId);
            Orden orden = ordenOptional.get();

            orden.setRepartidor(repartidor);

            Specification<Estado> filterByEnCamino = specEstado.findByProperty("denominacion", "en camino");
            Optional<Estado> estado = estadoRepository.findOne(Specification.where(filterByEnCamino));

            orden.setEstado(estado.get());

            orden = baseRepository.save(orden);

            return orden;


        }catch (Exception e){
            throw new Exception(e.getMessage());
        }
    }


    /*
     * @desc This method controls the existence of the product stock
     * @return False if the stock is insufficient for any product, true if the stock is sufficient
     * */
    @Override
    public boolean controlStock(List<DetalleOrden> detalles) {
        for (DetalleOrden detalleOrdenAux : detalles){
            if(detalleOrdenAux.getArticuloManufacturado() != null){
                for (DetalleReceta detalleReceta: detalleOrdenAux.getArticuloManufacturado().getDetallesReceta()){
                    if (detalleReceta.getInsumo().getStockActual() < (detalleReceta.getCantidad() * detalleOrdenAux.getCantidad())){
                        return false;
                    }
                }
            }

            if(detalleOrdenAux.getInsumo() != null){
                if (detalleOrdenAux.getInsumo().getStockActual() < detalleOrdenAux.getCantidad()){
                    return false;
                }

            }
        }

        return true;
    }


    /*
     * @desc This method removes the stock of the articles present in the order details
     * and update articles in the database
     * @return List<DetallesOrden> detalles or new Exception()
     * */
    @Override
    public List<DetalleOrden> removeStock(List<DetalleOrden> detalles) throws Exception {

        try{
            Optional<ArticuloInsumo> insumoOptional;
            ArticuloInsumo articuloInsumo;

            Timestamp timestamp = new Timestamp(System.currentTimeMillis());

            for (DetalleOrden detalleOrdenAux : detalles){
                if (detalleOrdenAux.getArticuloManufacturado() != null){

                    for (DetalleReceta detalleReceta: detalleOrdenAux.getArticuloManufacturado().getDetallesReceta()){

                        insumoOptional = insumoRepository.findById(detalleReceta.getInsumo().getId());
                        articuloInsumo = insumoOptional.get();
                        articuloInsumo.setStockActual(articuloInsumo.getStockActual() - (detalleReceta.getCantidad() * detalleOrdenAux.getCantidad()));
                        articuloInsumo.getHistorialStock().add(new HistorialStock((detalleReceta.getCantidad() * detalleOrdenAux.getCantidad()), timestamp, false));
                        articuloInsumo.setUltimaActualizacion(timestamp);
                        articuloInsumo = insumoRepository.save(articuloInsumo);
                        detalleReceta.setInsumo(articuloInsumo);
                        detalleReceta.setUltimaActualizacion(timestamp);
                        detalleOrdenAux.setUltimaActualizacion(timestamp);
                    }
                }

                if(detalleOrdenAux.getInsumo() != null){
                    insumoOptional = insumoRepository.findById(detalleOrdenAux.getInsumo().getId());
                    articuloInsumo = insumoOptional.get();
                    articuloInsumo.setStockActual(articuloInsumo.getStockActual() - detalleOrdenAux.getCantidad());
                    articuloInsumo.getHistorialStock().add(new HistorialStock(detalleOrdenAux.getCantidad(), timestamp, false));
                    articuloInsumo.setUltimaActualizacion(timestamp);
                    articuloInsumo = insumoRepository.save(articuloInsumo);
                    detalleOrdenAux.setInsumo(articuloInsumo);
                    detalleOrdenAux.setUltimaActualizacion(timestamp);
                }
            }

            return detalles;

        } catch (Exception e){
            throw new Exception(e.getMessage());
        }
    }

    /*
     * @desc This method gets all the orders paged where state is "EN PROCESO" or "DEMORADO"
     * @return Map<String, Object> ordenesEnCocina or new Exception()
     * */
    @Override
    public Map<String, Object> ordenesEnCocina(String filter, int page, int size, String sortBy, String direction) throws Exception {
        try {
            Pageable pageable;
            if (direction.equals("desc")) {
                pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, sortBy));
            } else {
                pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, sortBy));
            }

            Page<Orden> entityPage;

            SearchSpecification<Orden> spec = new SearchSpecification<Orden>();
            Specification<Orden> isNotDeleted = spec.isNotDeleted();

            Specification<Orden> filterByDemorado = spec.findByEstado("demorado");
            Specification<Orden> filterByEnProceso = spec.findByEstado("en proceso");


            if(filter == null || filter.equals("")){
                entityPage = baseRepository.findAll(Specification.where(isNotDeleted).and(Specification.where(filterByEnProceso).or(filterByDemorado)),pageable);
            } else {
                Specification<Orden> filterByEstado = spec.findByEstado(filter);
                Specification<Orden> filterById = spec.findByProperty("descripcion", filter);
                Specification<Orden> filterByFormaPago = spec.findByProperty("formaPago", filter);
                Specification<Orden> filterByNombreCliente = spec.findByForeignAttribute("cliente", "nombre", filter);
                Specification<Orden> filterByApellidoCliente = spec.findByForeignAttribute("cliente", "apellido", filter);

                entityPage = baseRepository.findAll(Specification.where(isNotDeleted).and(Specification.where(filterByEnProceso).or(filterByDemorado))
                        .and(Specification.where(filterByEstado)
                                .or(filterById)
                                .or(filterByFormaPago)
                                .or(filterByNombreCliente)
                                .or(filterByApellidoCliente)
                        ), pageable);
            }

            List<Orden> entities = entityPage.getContent();

            Map<String, Object> response = new HashMap<>();
            response.put("payload", entities);
            response.put("length", entityPage.getTotalElements());

            return response;
        } catch (Exception e) {
            throw new Exception(e.getMessage());
        }
    }


    /*
     * @desc This method gets all orders by Cliente where state is "PENDIENTE", "EN PROCESO", "DEMORADO",
     *       "LISTO" or "EN CAMINO"
     * @return List<Orden> ordenesPendientes or new Exception()
     * */
    @Override
    public List<Orden> getOrdenesPendientes(String clienteUid) throws Exception {
        try{

            SearchSpecification<Orden> spec = new SearchSpecification<Orden>();
            Specification<Orden> isNotDeleted = spec.isNotDeleted();

            Specification<Orden> filterByCliente = spec.findByForeignAttribute("cliente", "uid", clienteUid);
            Specification<Orden> filterByPendiente = spec.findByEstado("pendiente");
            Specification<Orden> filterByEnProceso = spec.findByEstado("en proceso");
            Specification<Orden> filterByDemorado = spec.findByEstado("demorado");
            Specification<Orden> filterByListo = spec.findByEstado("listo");
            Specification<Orden> filterByEnCamino = spec.findByEstado("en camino");

            return baseRepository.findAll(Specification.where(isNotDeleted).and(Specification.where(filterByCliente)).and(Specification.where(filterByPendiente)
                    .or(filterByEnProceso)
                    .or(filterByDemorado)
                    .or(filterByListo)
                    .or(filterByEnCamino)
            ));

        } catch (Exception e){
            throw new Exception(e.getMessage());
        }
    }

    /*
     * @desc This method gets all orders by Cliente where state is "ENTREGADO" or "CANCELADO"
     * @return List<Orden> ordenesPasadas or new Exception()
     * */
    @Override
    public List<Orden> getOrdenesPasadas(String clienteUid) throws Exception {
        try{

            SearchSpecification<Orden> spec = new SearchSpecification<Orden>();
            Specification<Orden> isNotDeleted = spec.isNotDeleted();

            Specification<Orden> filterByCliente = spec.findByForeignAttribute("cliente", "uid", clienteUid);
            Specification<Orden> filterByCancelado = spec.findByEstado("cancelado");
            Specification<Orden> filterByEntregado = spec.findByEstado("entregado");

            return baseRepository.findAll(Specification.where(isNotDeleted).and(Specification.where(filterByCliente))
                    .and(Specification.where(filterByCancelado).or(filterByEntregado)
            ));

        } catch (Exception e){
            throw new Exception(e.getMessage());
        }
    }
}
