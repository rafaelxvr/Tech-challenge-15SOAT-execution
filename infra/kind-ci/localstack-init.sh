#!/bin/sh
# Bootstrap idempotente do LocalStack para o execution (filas e tabelas do contrato do Plan 0).
# Roda DENTRO do pod do LocalStack via `kubectl exec -i ... sh -s`: só fala com localhost:4566. Credenciais fictícias.
set -eu
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=us-east-1
export AWS_EC2_METADATA_DISABLED=true AWS_ENDPOINT_URL=http://localhost:4566
ACCOUNT=000000000000

create_fifo() { # nome, dlq-ou-vazio
  name="$1"; dlq="${2:-}"
  if awslocal sqs get-queue-url --queue-name "$name" >/dev/null 2>&1; then echo "fila $name: existe"; return 0; fi
  redrive=""
  if [ -n "$dlq" ]; then
    redrive=",\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"arn:aws:sqs:us-east-1:$ACCOUNT:$dlq\\\",\\\"maxReceiveCount\\\":\\\"5\\\"}\""
  fi
  printf '{"FifoQueue":"true","ContentBasedDeduplication":"false","VisibilityTimeout":"60"%s}' "$redrive" > /tmp/attrs.json
  awslocal sqs create-queue --queue-name "$name" --attributes file:///tmp/attrs.json >/dev/null
  echo "fila $name: criada"
}

for base in execution-commands os-saga-replies; do
  create_fifo "$base-dlq.fifo" ""
  create_fifo "$base.fifo" "$base-dlq.fifo"
done

create_table() { # nome, demais argumentos do create-table
  name="$1"; shift
  if awslocal dynamodb describe-table --table-name "$name" >/dev/null 2>&1; then echo "tabela $name: existe"; return 0; fi
  awslocal dynamodb create-table --table-name "$name" --billing-mode PAY_PER_REQUEST "$@" >/dev/null
  echo "tabela $name: criada"
}

create_table oficina-phase4-execucoes \
  --attribute-definitions AttributeName=execucaoId,AttributeType=S AttributeName=filaChave,AttributeType=S \
    AttributeName=osId,AttributeType=S AttributeName=enfileiradoEm,AttributeType=N \
  --key-schema AttributeName=execucaoId,KeyType=HASH \
  --global-secondary-indexes \
    'IndexName=fila-index,KeySchema=[{AttributeName=filaChave,KeyType=HASH},{AttributeName=enfileiradoEm,KeyType=RANGE}],Projection={ProjectionType=ALL}' \
    'IndexName=os-index,KeySchema=[{AttributeName=osId,KeyType=HASH},{AttributeName=enfileiradoEm,KeyType=RANGE}],Projection={ProjectionType=ALL}'
create_table oficina-phase4-execucao-outbox \
  --attribute-definitions AttributeName=messageId,AttributeType=S AttributeName=estado,AttributeType=S \
    AttributeName=disponivelEm,AttributeType=N AttributeName=osId,AttributeType=S AttributeName=sequencia,AttributeType=N \
  --key-schema AttributeName=messageId,KeyType=HASH \
  --global-secondary-indexes \
    'IndexName=estado-disponivel-index,KeySchema=[{AttributeName=estado,KeyType=HASH},{AttributeName=disponivelEm,KeyType=RANGE}],Projection={ProjectionType=ALL}' \
    'IndexName=os-sequencia-index,KeySchema=[{AttributeName=osId,KeyType=HASH},{AttributeName=sequencia,KeyType=RANGE}],Projection={ProjectionType=ALL}'
create_table oficina-phase4-execucao-inbox \
  --attribute-definitions AttributeName=messageId,AttributeType=S \
  --key-schema AttributeName=messageId,KeyType=HASH
echo "init: ok"
