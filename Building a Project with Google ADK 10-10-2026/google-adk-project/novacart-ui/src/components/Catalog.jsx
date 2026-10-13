import { useEffect, useState } from 'react'
import { api } from '../lib/api'

/**
 * The business data behind every plan.
 *
 * Here so a number in a resolution can be checked against something. When the planner says it
 * will move three units from Pune, this is the page that proves Pune had them.
 */
export default function Catalog({ refreshKey }) {
  const [data, setData] = useState(null)

  useEffect(() => {
    api.catalog().then(setData).catch(() => {})
  }, [refreshKey])

  if (!data) {
    return <div className="empty">Loading</div>
  }

  return (
    <>
      <div className="page-head">
        <h1>Orders</h1>
        <span className="sub">
          {data.orders.length} orders · {data.stock.length} stock lines
        </span>
      </div>

      <div className="body">
        <div className="section">
          <h2>Stock</h2>
          <p className="note">
            NovaCart warehouses only. Partner Logistics is a different company and is not
            listed here.
          </p>
          <table>
            <thead>
              <tr>
                <th style={{ width: 130 }}>Product</th>
                <th style={{ width: 130 }}>Warehouse</th>
                <th className="num" style={{ width: 80 }}>Units</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {[...data.stock]
                .sort((a, b) =>
                  a.sku.localeCompare(b.sku) || a.warehouse.localeCompare(b.warehouse))
                .map((row) => (
                <tr key={row.sku + row.warehouse}>
                  <td className="id">{row.sku}</td>
                  <td>{row.warehouse}</td>
                  <td className="num">{row.units}</td>
                  <td>{row.units === 0 && <span className="st bad">out of stock</span>}</td>
                </tr>
                ))}
            </tbody>
          </table>
        </div>

        <div className="section">
          <h2>Orders</h2>
          <table>
            <thead>
              <tr>
                <th style={{ width: 110 }}>Order</th>
                <th style={{ width: 90 }}>Customer</th>
                <th>Items</th>
                <th className="num" style={{ width: 90 }}>Total</th>
                <th style={{ width: 150 }}>Status</th>
              </tr>
            </thead>
            <tbody>
              {data.orders.map((order) => (
                <tr key={order.id}>
                  <td className="id">{order.id}</td>
                  <td className="id">{order.customerId}</td>
                  <td className="dim">
                    {order.items.map((item) => `${item.quantity} x ${item.name}`).join(', ')}
                  </td>
                  <td className="num">{order.totalRupees.toLocaleString('en-IN')}</td>
                  <td>
                    <span className={statusClass(order.status)}>{label(order.status)}</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </>
  )
}

function statusClass(status) {
  if (status === 'DELIVERED') return 'st ok'
  if (status === 'AWAITING_STOCK') return 'st bad'
  if (status === 'SHIPPED') return 'st run'
  return 'st warn'
}

function label(status) {
  return status.charAt(0) + status.slice(1).toLowerCase().replace('_', ' ')
}
