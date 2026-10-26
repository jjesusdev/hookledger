# k3s — manifiestos

Despliegue de referencia para un clúster k3s de un solo nodo o para cualquier Kubernetes 1.28+.
**No está desplegado**: en este repositorio los manifiestos se renderizan y validan en CI, no hay
clúster ni URL pública.

## Qué incluye

| Recurso | Uso |
|---|---|
| `namespace.yaml` | namespace `hookledger` |
| `configmap.yaml` | configuración no secreta; reemplazar las URLs de SQS |
| `secret.yaml` | **plantilla**; no contiene secretos reales |
| `serviceaccount.yaml` | sin token automontado; en EKS se le adjunta IRSA |
| `postgres.yaml` | PostgreSQL 16 con PVC de 5 Gi, solo para demo |
| `app.yaml` | 2 réplicas de la API, probes de startup/readiness/liveness |
| `worker.yaml` | 1 réplica del consumidor SQS, outbox y demo apagados |
| `service.yaml` | Service ClusterIP y PodDisruptionBudget |

## Renderizar y validar

```bash
kubectl kustomize deploy/k8s > /tmp/hookledger.yaml
docker run --rm -v /tmp:/tmp ghcr.io/yannh/kubeconform:v0.8.0 \
  -strict -summary -kubernetes-version 1.32.0 /tmp/hookledger.yaml
```

En CI esto corre en el job `k8s-manifests`, junto con `kubectl kustomize` para detectar referencias
rotas.

## Aplicar (cuando exista un clúster)

```bash
export KUBECONFIG=...
kubectl apply -k deploy/k8s
kubectl -n hookledger rollout status deploy/hookledger --timeout=120s
kubectl -n hookledger rollout status deploy/hookledger-worker --timeout=120s
```

Antes de aplicar:

1. Crear el Secret real (ver el comentario en `secret.yaml`). No aplicar el placeholder.
2. Reemplazar `SQS_QUEUE_URL` y `SQS_DLQ_URL` en el ConfigMap por el output de Terraform.
3. Reemplazar `DEMO_TARGET_URL`; si no hay consumidor de demo, apuntar a un endpoint propio.
4. Publicar la imagen en GHCR y fijar el tag en `kustomization.yaml`; `latest` solo sirve para el
   ejemplo.
5. En EKS, borrar `AWS_ACCESS_KEY_ID` y `AWS_SECRET_ACCESS_KEY` del Secret y usar IRSA.

## Límites deliberados

- PostgreSQL dentro del clúster no tiene backups ni alta disponibilidad; en producción va a RDS o a un
  operador con backups verificados.
- No hay Ingress ni TLS: depende del dominio y del controlador del clúster.
- No hay HPA: el worker escala con consumidores de SQS, pero la métrica correcta (mensajes por
  consumidor) todavía no está expuesta.
- No se aplicó ArgoCD. Los manifiestos son la fuente de verdad, pero la sincronización GitOps queda
  para cuando haya clúster.
