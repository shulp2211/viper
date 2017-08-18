var module = angular.module('de.imi.marw.viper.inspector', [
  'de.imi.marw.viper.igv.image',
  'de.imi.marw.viper.variant-table.service'
])
.controller('InspectorPageCtrl', function (VariantTableService, $q, $http, $interval) {

  var Ctrl = this;

  Ctrl.tableSize      = null;
  Ctrl.index          = null;
  Ctrl.currentVariant = null;
  Ctrl.relatedVariants = [ ];
  Ctrl.columnNames = [ ];

  Ctrl.init = init;
  Ctrl.onIndexChange = onIndexChange;
  Ctrl.variantPropertyToString = VariantTableService.variantPropertyToString;
  Ctrl.sendDecision = sendDecision;
  Ctrl.count = 0;
  Ctrl.init();

  function init() {
    $q.all([
      VariantTableService.getSize(),
      VariantTableService.getRelatedColumnNames()
    ]).then(function (data) {

      var tableSize = data[0];
      var columnNames = data[1];

      Ctrl.tableSize = tableSize;
      Ctrl.index     = VariantTableService.clickedVariantIndex == null ? 0 : VariantTableService.clickedVariantIndex;
      VariantTableService.clickedVariantIndex = null;
      Ctrl.columnNames = columnNames;

      Ctrl.onIndexChange();
    });

  }

  function sendDecision (decision) {

    var promise = $http.put('/api/variant-table/decision', {}, {
      params: {
        index: Ctrl.index,
        decision: decision
      }
    });

    if (Ctrl.index >= 0 && Ctrl.index < Ctrl.tableSize - 1) {
      Ctrl.index++;
      Ctrl.onIndexChange();
    } else {
      promise.then(Ctrl.onIndexChange);
    }
  }

  function onIndexChange () {

    if (Ctrl.tableSize == 0) return Ctrl.currentVariant = null;

    VariantTableService.scheduleSnapshot(Ctrl.index);

    $q.all([
        VariantTableService.getTableRow(Ctrl.index),
        VariantTableService.getRelatedCalls(Ctrl.index),
    ]).then(function (data) {
      Ctrl.currentVariant = data[0];
      Ctrl.relatedVariants = data[1];
    });

  }

})
