import TopologyEditor from './topology/TopologyEditor'

// First real screen: the conveyor topology editor (build.md §8, §11). More screens (operator
// dashboards, order/inventory views) will be routed alongside this as they're built.
export default function App() {
  return <TopologyEditor />
}
